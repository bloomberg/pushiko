/*
 * Copyright 2025 Bloomberg Finance L.P.
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

package com.bloomberg.pushiko.pool

import kotlinx.coroutines.test.runTest
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.toDuration

internal class AnyPoolable(
    override val maximumPermits: Int = 100
) : Poolable<Any>(Any()) {
    override val isAlive: Boolean = true

    override val isCanAcquire
        get() = allocatedPermits < maximumPermits

    override val isShouldAcquire
        get() = isCanAcquire
}

internal class CommonMuxPoolStressTest {
    private val factory = object : Factory<AnyPoolable>, Recycler<Any> {
        private var _allocations = 0

        override val allocations: Int
            get() = _allocations

        override suspend fun close() = Unit

        override suspend fun make(): AnyPoolable {
            ++_allocations
            return AnyPoolable()
        }

        override fun recycle(obj: Any) {
            --_allocations
        }
    }
    private val pool = CommonMuxPool(
        configuration = PoolConfiguration(
            acquisitionAttemptsThreshold = 3,
            maximumPendingAcquisitions = 1_000,
            maximumSize = 10,
            minimumSize = 4,
            reaperDelay = 1L.minutes,
            summaryInterval = 5L.minutes
        ),
        factory,
        factory
    )

    @BeforeEach
    fun setUp() {
        runTest {
            pool.prepare()
        }
    }

    @AfterEach
    fun tearDown() {
        runTest {
            pool.close()
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun work(): Any = pool.withPermit(60L.toDuration(DurationUnit.SECONDS)) {
        ""
    }

    // @Test
    fun stressTest() = StressOptions().check(this::class)
}
