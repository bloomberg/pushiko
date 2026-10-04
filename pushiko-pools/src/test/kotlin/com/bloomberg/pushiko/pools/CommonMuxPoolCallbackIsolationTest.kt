/*
 * Copyright 2026 Bloomberg Finance L.P.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bloomberg.pushiko.pools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolCallbackIsolationTest {
    private class AlwaysGrowPoolable : Poolable<Any>(Any()) {
        override val maximumPermits: Int = 100
        override val isAlive: Boolean = true
        override val isCanAcquire: Boolean = true
        override val isShouldAcquire: Boolean = false
    }

    private class CallbackFactory(
        private val onMake: (Int) -> Unit = {},
        private val onRecycle: (Int) -> Unit = {}
    ) : Factory<AlwaysGrowPoolable>, Recycler<Any> {
        private val allocationCount = AtomicInteger()
        private val makeCount = AtomicInteger()
        private val recycleCount = AtomicInteger()

        override val allocations: Int
            get() = allocationCount.get()

        val recyclingAttempts: Int
            get() = recycleCount.get()

        override suspend fun close() = Unit

        override suspend fun make(): AlwaysGrowPoolable {
            onMake(makeCount.incrementAndGet())
            allocationCount.incrementAndGet()
            return AlwaysGrowPoolable()
        }

        override fun recycle(obj: Any) {
            onRecycle(recycleCount.incrementAndGet())
            allocationCount.decrementAndGet()
        }
    }

    private fun newPool(
        factory: CallbackFactory,
        maximumSize: Int,
        reaperDelay: kotlin.time.Duration = 10L.minutes
    ) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumPendingAcquisitions = 100,
            maximumSize = maximumSize,
            minimumSize = 1,
            reaperDelay = reaperDelay,
            summaryInterval = 5L.minutes
        ),
        factory,
        factory
    )

    private suspend fun growTo(pool: CommonMuxPool<Any, AlwaysGrowPoolable>, size: Int) {
        while (pool.metricsComponent.gauges(5L.seconds).allocatedSize < size) {
            pool.withPermit(5L.seconds) { }
            yield()
        }
    }

    @Test
    fun blockingFactoryDoesNotStallControlPlane() = runTest {
        val makeStarted = CompletableDeferred<Unit>()
        val allowMake = CountDownLatch(1)
        val factory = CallbackFactory(onMake = {
            if (it == 2) {
                makeStarted.complete(Unit)
                allowMake.await()
            }
        })
        val pool = newPool(factory, maximumSize = 2)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val acquisition = async { pool.withPermit(5L.seconds) { } }
                withTimeout(5L.seconds) { makeStarted.await() }

                assertEquals(1, pool.metricsComponent.gauges(1L.seconds).allocatedSize)

                allowMake.countDown()
                acquisition.await()
                withTimeout(5L.seconds) {
                    while (pool.metricsComponent.gauges(1L.seconds).allocatedSize != 2) {
                        yield()
                    }
                }
            }
        } finally {
            allowMake.countDown()
            pool.close()
        }
    }

    @Test
    fun blockingRecyclerDoesNotStallControlPlane() = runTest {
        val recycleStarted = CompletableDeferred<Unit>()
        val allowRecycle = CountDownLatch(1)
        val factory = CallbackFactory(onRecycle = {
            recycleStarted.complete(Unit)
            allowRecycle.await()
        })
        val pool = newPool(factory, maximumSize = 2, reaperDelay = 150L.milliseconds)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                growTo(pool, size = 2)
                withTimeout(5L.seconds) { recycleStarted.await() }

                assertEquals(2, pool.metricsComponent.gauges(1L.seconds).allocatedSize)
                assertEquals(2, factory.allocations)

                allowRecycle.countDown()
            }
        } finally {
            allowRecycle.countDown()
            pool.close()
        }
    }

    @Test
    fun recyclerFailureDoesNotAbortRemainingCleanupOrStrandReaperState() = runTest {
        val factory = CallbackFactory(onRecycle = {
            if (it == 1) {
                error("Simulated recycler failure")
            }
        })
        val pool = newPool(factory, maximumSize = 3, reaperDelay = 150L.milliseconds)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                growTo(pool, size = 3)

                withTimeout(5L.seconds) {
                    while (factory.recyclingAttempts != 2 || pool.isReaperScheduledForTest()) {
                        yield()
                    }
                }

                assertEquals(2, pool.metricsComponent.gauges(1L.seconds).allocatedSize)
                assertEquals(2, factory.allocations)
                assertEquals(2, factory.recyclingAttempts)
                assertFalse(pool.isReaperScheduledForTest())
            }
        } finally {
            pool.close()
        }
    }
}
