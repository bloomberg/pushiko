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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolPermitReleaseTest {
    private class HealthyFactory(
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

    private fun newPool(
        factory: HealthyFactory,
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
    fun permitIsReleasedWhenBlockThrows() = runTest {
        val factory = HealthyFactory(maximumPermits = 1)
        val pool = newPool(factory, minimumSize = 1, maximumSize = 1)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertFailsWith<IOException> {
                    pool.withPermit(Duration.INFINITE) { throw IOException("boom") }
                }
                pool.withPermit(5L.seconds) { }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun repeatedBlockFailuresDoNotLeakThePermit() = runTest {
        val factory = HealthyFactory(maximumPermits = 1)
        val pool = newPool(factory, minimumSize = 1, maximumSize = 1)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                repeat(20) {
                    assertFailsWith<IOException> {
                        pool.withPermit(5L.seconds) { throw IOException("boom") }
                    }
                }
                pool.withPermit(5L.seconds) { }
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun concurrentBlockFailuresDoNotStarvePermits() = runTest {
        val factory = HealthyFactory(maximumPermits = 4)
        val pool = newPool(factory, minimumSize = 1, maximumSize = 1)
        val workers = 8
        val iterations = 50
        val timeouts = AtomicInteger(0)
        try {
            withContext(Dispatchers.Default) {
                pool.prepare()
                (0 until workers).map {
                    launch {
                        repeat(iterations) {
                            val cause = runCatching {
                                pool.withPermit<Unit>(5L.seconds) { throw IOException("boom") }
                            }.exceptionOrNull()
                            if (cause is TimeoutCancellationException) {
                                timeouts.incrementAndGet()
                            }
                        }
                    }
                }.joinAll()
            }
            assertEquals(0, timeouts.get())
            withContext(Dispatchers.Default) {
                pool.withPermit(5L.seconds) { }
            }
        } finally {
            pool.close()
        }
    }
}
