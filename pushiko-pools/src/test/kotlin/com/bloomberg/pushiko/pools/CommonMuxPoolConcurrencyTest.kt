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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal class CommonMuxPoolConcurrencyTest {
    private class PeakTrackingFactory(
        private val maximumPermits: Int
    ) : Factory<AnyPoolable>, Recycler<Any> {
        private val live = AtomicInteger(0)
        private val peak = AtomicInteger(0)

        val peakAllocations: Int
            get() = peak.get()

        override val allocations: Int
            get() = live.get()

        override suspend fun close() = Unit

        override suspend fun make(): AnyPoolable {
            val current = live.incrementAndGet()
            peak.getAndUpdate { max(it, current) }
            return AnyPoolable(maximumPermits = maximumPermits)
        }

        override fun recycle(obj: Any) {
            live.decrementAndGet()
        }
    }

    @Test
    fun concurrentAcquisitionsRespectCapacityAndAllComplete() = runTest {
        val minimumSize = 2
        val maximumSize = 8
        val factory = PeakTrackingFactory(maximumPermits = 1)
        val pool = CommonMuxPool(
            configuration = PoolConfiguration(
                acquisitionAttemptsThreshold = 3,
                maximumPendingAcquisitions = 10_000,
                maximumSize = maximumSize,
                minimumSize = minimumSize,
                reaperDelay = 10L.minutes,
                summaryInterval = 5L.minutes
            ),
            factory,
            factory
        )
        val workers = 16
        val iterations = 100
        val completed = AtomicInteger(0)
        var settledSize = -1
        try {
            withContext(Dispatchers.Default) {
                pool.prepare()
                (0 until workers).map {
                    launch {
                        repeat(iterations) {
                            pool.withPermit(Duration.INFINITE) { }
                            completed.incrementAndGet()
                        }
                    }
                }.joinAll()
                settledSize = pool.metricsComponent.gauges(Duration.INFINITE).allocatedSize
            }
            assertEquals(workers * iterations, completed.get())
            assertTrue(
                factory.peakAllocations <= maximumSize,
                "Peak allocations ${factory.peakAllocations} exceeded maximumSize $maximumSize"
            )
            assertTrue(
                settledSize in minimumSize..maximumSize,
                "Settled size $settledSize outside [$minimumSize, $maximumSize]"
            )
        } finally {
            pool.close()
        }
    }
}
