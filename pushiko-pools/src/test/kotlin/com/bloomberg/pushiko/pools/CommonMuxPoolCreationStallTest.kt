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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolCreationStallTest {
    private class HangingFactory(
        private val maximumPermits: Int = 10
    ) : Factory<AnyPoolable>, Recycler<Any> {
        private val liveAllocations = AtomicInteger(0)
        private val makeCallCount = AtomicInteger(0)
        private val gate = CompletableDeferred<Unit>()
        private val firstMake = CompletableDeferred<Unit>()

        override val allocations: Int
            get() = liveAllocations.get()

        val makeCalls: Int
            get() = makeCallCount.get()

        fun release() {
            gate.complete(Unit)
        }

        suspend fun awaitFirstMake() {
            firstMake.await()
        }

        override suspend fun close() = Unit

        override suspend fun make(): AnyPoolable {
            makeCallCount.incrementAndGet()
            firstMake.complete(Unit)
            gate.await()
            liveAllocations.incrementAndGet()
            return AnyPoolable(maximumPermits = maximumPermits)
        }

        override fun recycle(obj: Any) {
            liveAllocations.decrementAndGet()
        }
    }

    private fun newPool(
        factory: HangingFactory,
        minimumSize: Int,
        maximumSize: Int
    ) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumPendingAcquisitions = 1_000,
            maximumSize = maximumSize,
            minimumSize = minimumSize,
            reaperDelay = 10L.minutes,
            summaryInterval = 5L.minutes
        ),
        factory,
        factory
    )

    @Test
    fun foregroundAcquisitionTimesOutWhileCreationHangs() = runTest {
        val factory = HangingFactory()
        val pool = newPool(factory, minimumSize = 0, maximumSize = 1)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                val acquisition = launch {
                    assertFailsWith<TimeoutCancellationException> {
                        pool.withPermit(300L.milliseconds) { }
                    }
                }
                factory.awaitFirstMake()
                assertEquals(1, factory.makeCalls)
                assertEquals(0, factory.allocations)
                acquisition.join()
                assertEquals(0, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun hangingCreationIsNotDuplicatedAcrossRepeatedAcquisitions() = runTest {
        val factory = HangingFactory()
        val pool = newPool(factory, minimumSize = 0, maximumSize = 4)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                assertFailsWith<TimeoutCancellationException> {
                    pool.withPermit(200L.milliseconds) { }
                }
                repeat(2) {
                    assertFailsWith<TimeoutCancellationException> {
                        pool.withPermit(200L.milliseconds) { }
                    }
                }
                assertEquals(1, factory.makeCalls)
                assertEquals(0, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun poolRecoversAfterHangingCreationCompletes() = runTest {
        val factory = HangingFactory()
        val pool = newPool(factory, minimumSize = 0, maximumSize = 1)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                val acquisition = launch {
                    pool.withPermit(5L.seconds) { }
                }
                factory.awaitFirstMake()
                assertEquals(0, factory.allocations)
                factory.release()
                acquisition.join()
                assertEquals(1, factory.makeCalls)
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }
}
