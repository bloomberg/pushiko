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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolFactoryFailureTest {
    private class ThrowingFactory(
        @Volatile private var failuresRemaining: Int,
        private val maximumPermits: Int = 10
    ) : Factory<AnyPoolable>, Recycler<Any> {
        private var _allocations = 0

        override val allocations: Int
            get() = _allocations

        fun recover() {
            failuresRemaining = 0
        }

        override suspend fun close() = Unit

        override suspend fun make(): AnyPoolable {
            if (failuresRemaining > 0) {
                --failuresRemaining
                throw IOException("Simulated connection failure")
            }
            ++_allocations
            return AnyPoolable(maximumPermits = maximumPermits)
        }

        override fun recycle(obj: Any) {
            --_allocations
        }
    }

    private fun newPool(
        factory: ThrowingFactory,
        minimumSize: Int,
        maximumSize: Int
    ) = CommonMuxPool(
        configuration = PoolConfiguration(
            acquisitionAttemptsThreshold = 3,
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
    fun prepareToleratesTotalFactoryFailureAndStaysCoherent() = runTest {
        val factory = ThrowingFactory(failuresRemaining = Int.MAX_VALUE)
        val pool = newPool(factory, minimumSize = 2, maximumSize = 4)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                assertEquals(0, pool.prepare())
                assertEquals(0, pool.allocatedSize())
                factory.recover()
                assertEquals(2, pool.prepare())
                assertEquals(2, pool.allocatedSize())
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun prepareCreatesSurvivorsWhenSomeCreationsFail() = runTest {
        val factory = ThrowingFactory(failuresRemaining = 1)
        val pool = newPool(factory, minimumSize = 3, maximumSize = 5)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                assertEquals(2, pool.prepare())
                assertEquals(2, pool.allocatedSize())
                assertEquals(1, pool.prepare())
                assertEquals(3, pool.allocatedSize())
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun acquisitionRecoversAfterTransientFactoryFailure() = runTest {
        val factory = ThrowingFactory(failuresRemaining = 1)
        val pool = newPool(factory, minimumSize = 0, maximumSize = 1)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                assertEquals(0, pool.prepare())
                assertFailsWith<TimeoutCancellationException> {
                    pool.withPermit(500L.milliseconds) { }
                }
                pool.withPermit(5L.seconds) { }
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }
}
