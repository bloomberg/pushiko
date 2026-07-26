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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolScalingTest {
    private class CountingFactory(
        private val maximumPermits: Int
    ) : Factory<AnyPoolable>, Recycler<Any> {
        private var _allocations = 0

        override val allocations: Int
            get() = _allocations

        override suspend fun close() = Unit

        override suspend fun make(): AnyPoolable {
            ++_allocations
            return AnyPoolable(maximumPermits = maximumPermits)
        }

        override fun recycle(obj: Any) {
            --_allocations
        }
    }

    private class BandedPoolable(
        private val low: Int,
        override val maximumPermits: Int
    ) : Poolable<Any>(Any()) {
        override val isAlive: Boolean = true
        override val isCanAcquire: Boolean
            get() = allocatedPermits < maximumPermits
        override val isShouldAcquire: Boolean
            get() = allocatedPermits < low
    }

    private class BandedFactory(
        private val low: Int,
        private val high: Int
    ) : Factory<BandedPoolable>, Recycler<Any> {
        private var _allocations = 0

        override val allocations: Int
            get() = _allocations

        override suspend fun close() = Unit

        override suspend fun make(): BandedPoolable {
            ++_allocations
            return BandedPoolable(low = low, maximumPermits = high)
        }

        override fun recycle(obj: Any) {
            --_allocations
        }
    }

    private class DeadPoolable : Poolable<Any>(Any()) {
        override val maximumPermits: Int = 0
        override val isAlive: Boolean = false
        override val isCanAcquire: Boolean = false
        override val isShouldAcquire: Boolean = false
    }

    private class SaturatedPoolable : Poolable<Any>(Any()) {
        override val maximumPermits: Int = 1
        override val isAlive: Boolean = true
        override val isCanAcquire: Boolean = false
        override val isShouldAcquire: Boolean = false
    }

    private class SaturatedFactory : Factory<SaturatedPoolable>, Recycler<Any> {
        private var _allocations = 0

        override val allocations: Int
            get() = _allocations

        override suspend fun close() = Unit

        override suspend fun make(): SaturatedPoolable {
            ++_allocations
            return SaturatedPoolable()
        }

        override fun recycle(obj: Any) {
            --_allocations
        }
    }

    private class MixedFactory(
        private val deadCount: Int
    ) : Factory<Poolable<Any>>, Recycler<Any> {
        private var _allocations = 0
        private var created = 0

        override val allocations: Int
            get() = _allocations

        override suspend fun close() = Unit

        override suspend fun make(): Poolable<Any> {
            ++_allocations
            return if (created++ < deadCount) {
                DeadPoolable()
            } else {
                AnyPoolable()
            }
        }

        override fun recycle(obj: Any) {
            --_allocations
        }
    }

    private class ProbeCountingPoolable(
        override val maximumPermits: Int,
        private val onProbe: () -> Unit
    ) : Poolable<Any>(Any()) {
        override val isAlive: Boolean = true
        override val isCanAcquire: Boolean
            get() {
                onProbe()
                return true
            }
        override val isShouldAcquire: Boolean = false
    }

    private class ProbeCountingFactory : Factory<ProbeCountingPoolable>, Recycler<Any> {
        var probeCount = 0
        private var _allocations = 0

        override val allocations: Int
            get() = _allocations

        override suspend fun close() = Unit

        override suspend fun make(): ProbeCountingPoolable {
            ++_allocations
            return ProbeCountingPoolable(maximumPermits = 100) { ++probeCount }
        }

        override fun recycle(obj: Any) {
            --_allocations
        }
    }

    private fun newPool(
        factory: CountingFactory,
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
    fun reusesHealthyPoolableWithoutScaling() = runTest {
        val factory = CountingFactory(maximumPermits = 10)
        val pool = newPool(factory, minimumSize = 1, maximumSize = 4)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                repeat(5) {
                    pool.withPermit(Duration.INFINITE) { }
                }
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun coldStartCreatesOnDemandWhenMinimumSizeZero() = runTest {
        val factory = CountingFactory(maximumPermits = 10)
        val pool = newPool(factory, minimumSize = 0, maximumSize = 2)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                assertEquals(0, pool.prepare())
                assertEquals(0, factory.allocations)
                pool.withPermit(Duration.INFINITE) {
                    assertEquals(1, factory.allocations)
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun coldStartReusesCreatedPoolableOnSubsequentAcquisition() = runTest {
        val factory = CountingFactory(maximumPermits = 10)
        val pool = newPool(factory, minimumSize = 0, maximumSize = 2)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                repeat(3) {
                    pool.withPermit(Duration.INFINITE) { }
                }
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun growsPreemptivelyBeforeSaturation() = runTest {
        val factory = BandedFactory(low = 1, high = 4)
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 2,
                minimumSize = 1,
                reaperDelay = 10L.minutes,
                summaryInterval = 5L.minutes
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(1, factory.allocations)
                pool.withPermit(Duration.INFINITE) {
                    pool.withPermit(5L.seconds) {
                        assertEquals(2, factory.allocations)
                    }
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun scalesWhenSaturatedUnderPressure() = runTest {
        val factory = CountingFactory(maximumPermits = 1)
        val pool = newPool(factory, minimumSize = 1, maximumSize = 2)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(1, factory.allocations)
                pool.withPermit(Duration.INFINITE) {
                    pool.withPermit(5L.seconds) {
                        assertEquals(2, factory.allocations)
                    }
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun reaperPrunesBackToMinimumSize() = runTest {
        val factory = CountingFactory(maximumPermits = 1)
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 2,
                minimumSize = 1,
                reaperDelay = 300L.milliseconds,
                summaryInterval = 5L.minutes
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(1, factory.allocations)
                pool.withPermit(Duration.INFINITE) {
                    pool.withPermit(5L.seconds) {
                        assertEquals(2, factory.allocations)
                    }
                }
                withTimeout(5L.seconds) {
                    while (pool.metricsComponent.gauges(Duration.INFINITE).allocatedSize > 1) {
                        yield()
                    }
                }
                assertEquals(1, pool.metricsComponent.gauges(Duration.INFINITE).allocatedSize)
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun reacquiresAfterDrainingToZeroWhenMinimumSizeZero() = runTest {
        val factory = CountingFactory(maximumPermits = 1)
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 2,
                minimumSize = 0,
                reaperDelay = 300L.milliseconds,
                summaryInterval = 5L.minutes
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.withPermit(Duration.INFINITE) {
                    assertEquals(1, factory.allocations)
                }
                withTimeout(5L.seconds) {
                    while (pool.metricsComponent.gauges(Duration.INFINITE).allocatedSize > 0) {
                        yield()
                    }
                }
                assertEquals(0, pool.metricsComponent.gauges(Duration.INFINITE).allocatedSize)
                assertEquals(0, factory.allocations)
                pool.withPermit(Duration.INFINITE) {
                    assertEquals(1, factory.allocations)
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun acquiresFallbackWhenAtCapacityWithDegradedConnections() = runTest {
        val factory = BandedFactory(low = 2, high = 4)
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 3,
                minimumSize = 3,
                reaperDelay = 10L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 10,
                maximumSampledScan = 20
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(3, factory.allocations)
                repeat(3) {
                    repeat(2) {
                        pool.withPermit(Duration.INFINITE) { }
                    }
                }
                repeat(3) {
                    pool.withPermit(5L.seconds) { }
                }
                assertEquals(3, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun selectsLivePoolableEvenWhenDeadOnesAreInRotation() = runTest {
        val factory = MixedFactory(deadCount = 5)
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 10,
                minimumSize = 6,
                reaperDelay = 10L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 10,
                maximumSampledScan = 10
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(6, factory.allocations)
                pool.withPermit(Duration.INFINITE) {
                    assertEquals(6, factory.allocations)
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun doesNotLoopInfinitelyWhenAllPoolablesAreDead() = runTest {
        val factory = MixedFactory(deadCount = 5)
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 10,
                minimumSize = 5,
                reaperDelay = 10L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 10,
                maximumSampledScan = 10
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(5, factory.allocations)
                pool.withPermit(5L.seconds) {
                    assert(factory.allocations > 5) { "Pool should have grown beyond dead poolables" }
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun selectReturnsNullWithoutLoopingWhenAllAliveButSaturated() = runTest {
        val factory = SaturatedFactory()
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 3,
                minimumSize = 3,
                reaperDelay = 10L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 3,
                maximumSampledScan = 3
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(3, factory.allocations)
                assertNull(pool.selectPoolableForTest())
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun sampledScanProbesSqrtSubsetOfLargePool() = runTest {
        val factory = ProbeCountingFactory()
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 100,
                minimumSize = 20,
                reaperDelay = 10L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 4,
                maximumSampledScan = 50
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(20, pool.allocatedSize())
                factory.probeCount = 0
                assertNotNull(pool.selectPoolableForTest())
                assertEquals(8, factory.probeCount)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun sampledScanIsCappedByMaximumSampledScan() = runTest {
        val factory = ProbeCountingFactory()
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 100,
                minimumSize = 30,
                reaperDelay = 10L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 4,
                maximumSampledScan = 8
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(30, pool.allocatedSize())
                factory.probeCount = 0
                assertNotNull(pool.selectPoolableForTest())
                assertEquals(8, factory.probeCount)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun fullyScansPoolNotExceedingFullScanSize() = runTest {
        val factory = ProbeCountingFactory()
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 100,
                minimumSize = 8,
                reaperDelay = 10L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 10,
                maximumSampledScan = 20
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(8, pool.allocatedSize())
                factory.probeCount = 0
                assertNotNull(pool.selectPoolableForTest())
                assertEquals(8, factory.probeCount)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun scansEntirePoolWhenAtMaximumCapacity() = runTest {
        val factory = ProbeCountingFactory()
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1_000,
                maximumSize = 12,
                minimumSize = 12,
                reaperDelay = 10L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 4,
                maximumSampledScan = 6
            ),
            factory,
            factory
        )
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertEquals(12, pool.allocatedSize())
                factory.probeCount = 0
                assertNotNull(pool.selectPoolableForTest())
                assertEquals(12, factory.probeCount)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun selectPoolableMustNotSuspend() {
        // Assigning a non-suspend function reference fails to compile if selectPoolable ever becomes `suspend`.
        val reference: (CommonMuxPool<Any, AnyPoolable>) -> AnyPoolable? = CommonMuxPool<Any, AnyPoolable>::selectPoolable
        assertNotNull(reference)
    }
}
