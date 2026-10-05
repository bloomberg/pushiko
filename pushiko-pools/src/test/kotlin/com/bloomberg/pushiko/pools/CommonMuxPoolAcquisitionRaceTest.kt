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

import com.bloomberg.pushiko.pools.exceptions.PermitAcquisitionRetryLimitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolAcquisitionRaceTest {
    private class EligibilityRacePoolable : Poolable<Any>(Any()) {
        private val armedCapacityChecks = AtomicInteger()

        @Volatile
        private var armed = false

        @Volatile
        private var isDraining = false

        override val maximumPermits = 1
        override val isAlive: Boolean
            get() = !isDraining
        override val isCanAcquire: Boolean
            get() {
                if (armed && armedCapacityChecks.incrementAndGet() == 2) {
                    isDraining = true
                    notifyAvailabilityChanged()
                }
                return !isDraining && allocatedPermits < maximumPermits
            }
        override val isShouldAcquire = true

        fun arm() {
            armed = true
        }
    }

    private class RaceFactory : Factory<Poolable<Any>>, Recycler<Any> {
        private val allocationCount = AtomicInteger()
        private val recyclingCount = AtomicInteger()

        val racing = EligibilityRacePoolable()

        lateinit var replacement: Poolable<Any>
            private set

        override val allocations: Int
            get() = allocationCount.get()

        val recycled: Int
            get() = recyclingCount.get()

        override suspend fun close() = Unit

        override suspend fun make(): Poolable<Any> {
            allocationCount.incrementAndGet()
            return if (allocationCount.get() == 1) {
                racing
            } else {
                AnyPoolable().also { replacement = it }
            }
        }

        override fun recycle(obj: Any) {
            allocationCount.decrementAndGet()
            recyclingCount.incrementAndGet()
        }
    }

    @Test
    fun retriesWhenPoolableStartsDrainingBetweenSelectionAndPermitAcquisition() = runTest {
        val factory = RaceFactory()
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1,
                maximumSize = 1,
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
                factory.racing.arm()

                pool.withPermit(5L.seconds) {
                    assertSame(factory.replacement.value, it)
                }

                assertEquals(0, factory.racing.allocatedPermits)
                withTimeout(5L.seconds) {
                    while (factory.recycled != 1) {
                        yield()
                    }
                }
            }
        } finally {
            pool.close()
        }
    }

    private class NeverStabilizesPoolable : Poolable<Any>(Any()) {
        private var reads = 0
        override val maximumPermits = 1
        override val isAlive = true
        override val isCanAcquire: Boolean
            get() = ++reads % 2 == 0
        override val isShouldAcquire = true
    }

    private class NeverAcquirableFactory : Factory<Poolable<Any>>, Recycler<Any> {
        val poolable = NeverStabilizesPoolable()
        override val allocations: Int = 1
        override suspend fun close() = Unit
        override suspend fun make(): Poolable<Any> = poolable
        override fun recycle(obj: Any) = Unit
    }

    @Test
    fun failsWithinBoundedRetriesWhenSelectedPoolableNeverBecomesAcquirable() = runTest {
        val factory = NeverAcquirableFactory()
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 1,
                maximumSize = 1,
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
                assertFailsWith<PermitAcquisitionRetryLimitException> {
                    withTimeout(5L.seconds) {
                        pool.withPermit(Duration.INFINITE) { }
                    }
                }
                assertEquals(0, factory.poolable.allocatedPermits)
            }
        } finally {
            pool.close()
        }
    }
}
