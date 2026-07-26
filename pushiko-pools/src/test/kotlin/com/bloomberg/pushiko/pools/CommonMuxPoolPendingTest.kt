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

import com.bloomberg.pushiko.pools.exceptions.PendingAcquisitionLimitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolPendingTest {
    private class SinglePermitFactory : Factory<AnyPoolable>, Recycler<Any> {
        private var _allocations = 0

        override val allocations: Int
            get() = _allocations

        override suspend fun close() = Unit

        override suspend fun make(): AnyPoolable {
            ++_allocations
            return AnyPoolable(maximumPermits = 1)
        }

        override fun recycle(obj: Any) {
            --_allocations
        }
    }

    private fun newPool(
        factory: SinglePermitFactory,
        maximumPendingAcquisitions: Int
    ) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumPendingAcquisitions = maximumPendingAcquisitions,
            maximumSize = 1,
            minimumSize = 1,
            reaperDelay = 10L.minutes,
            summaryInterval = 5L.minutes
        ),
        factory,
        factory
    )

    @Test
    fun evictsOldestPendingAcquisitionWhenLimitExceeded() = runTest {
        val factory = SinglePermitFactory()
        val pool = newPool(factory, maximumPendingAcquisitions = 1)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderStarted = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderStarted.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderStarted.await()

                val first = async { runCatching { pool.withPermit(Duration.INFINITE) { } } }
                while (pool.pendingAcquisitionCount() == 0) {
                    yield()
                }

                val second = async { runCatching { pool.withPermit(Duration.INFINITE) { } } }
                val firstResult = first.await()
                assertTrue(firstResult.isFailure)
                assertSame(PendingAcquisitionLimitException, firstResult.exceptionOrNull())

                releaseHolder.complete(Unit)
                assertTrue(second.await().isSuccess)
                holder.join()
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun pendingAcquisitionIsServedWhenCapacityFrees() = runTest {
        val factory = SinglePermitFactory()
        val pool = newPool(factory, maximumPendingAcquisitions = 4)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderStarted = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderStarted.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderStarted.await()

                val waiter = async { runCatching { pool.withPermit(Duration.INFINITE) { } } }
                while (pool.pendingAcquisitionCount() == 0) {
                    yield()
                }

                releaseHolder.complete(Unit)
                assertTrue(waiter.await().isSuccess)
                holder.join()
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun prunesPendingAcquisitionAfterTimeout() = runTest {
        val factory = SinglePermitFactory()
        val pool = newPool(factory, maximumPendingAcquisitions = 10)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderStarted = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderStarted.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderStarted.await()

                assertFailsWith<TimeoutCancellationException> {
                    pool.withPermit(200L.milliseconds) { }
                }

                var observed: Int
                withTimeout(5L.seconds) {
                    while (pool.pendingAcquisitionCount().also { observed = it } != 0) {
                        yield()
                    }
                }
                assertEquals(0, observed)

                releaseHolder.complete(Unit)
                holder.join()
                pool.withPermit(Duration.INFINITE) { }
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }
}
