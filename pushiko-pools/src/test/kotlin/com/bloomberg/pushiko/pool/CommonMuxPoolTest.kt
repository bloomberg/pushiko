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

import com.bloomberg.pushiko.pool.exceptions.PoolClosedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)
internal class CommonMuxPoolTest {
    private val configuration = PoolConfiguration(
        acquisitionAttemptsThreshold = 3,
        maximumPendingAcquisitions = 1_000,
        maximumSize = 1,
        minimumSize = 1,
        reaperDelay = 1L.minutes,
        summaryInterval = 5L.minutes
    )
    private val factory = object : Factory<AnyPoolable>, Recycler<Any> {
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
    private val pool = CommonMuxPool(
        configuration = configuration,
        factory = factory,
        recycler = factory
    )

    @AfterEach
    fun tearDown() {
        runTest {
            pool.close()
        }
    }

    @Test
    fun closedRejects() = runTest {
        pool.close()
        assertFailsWith<PoolClosedException> {
            pool.withPermit(Duration.INFINITE) {}
        }
    }

    @Test
    fun preparePool() = runTest {
        assertEquals(configuration.minimumSize, pool.prepare())
    }

    @Test
    fun callingDispatcher() = runTest {
        withContext(Dispatchers.Default) {
            pool.withPermit(Duration.INFINITE) {
                assertSame(Dispatchers.Default, coroutineContext[CoroutineDispatcher.Key])
            }
        }
    }

    @Test
    fun withPermitZeroTimeout() = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            assertFailsWith<TimeoutCancellationException> {
                pool.withPermit(Duration.ZERO) {
                    fail("Acquisition should fail immediately")
                }
            }
        }
    }

    @Test
    fun acquisitionTimesOut() = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            pool.withPermit(Duration.INFINITE) {
                measureTime {
                    assertFailsWith<TimeoutCancellationException> {
                        pool.withPermit(100L.milliseconds) {
                            fail("Acquisition should fail")
                        }
                    }
                }.let {
                    assert(it.inWholeMilliseconds >= 100L) {
                        "Acquisition timed out within only $it"
                    }
                }
            }
        }
    }
}
