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

import com.bloomberg.pushiko.pools.exceptions.PoolClosedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

    private class CloseTrackingFactory(
        private val maximumPermits: Int = 1
    ) : Factory<AnyPoolable>, Recycler<Any> {
        private lateinit var poolable: AnyPoolable
        val closedPermitCount = CompletableDeferred<Int>()

        override val allocations: Int
            get() = if (::poolable.isInitialized) { 1 } else { 0 }

        override suspend fun close() {
            closedPermitCount.complete(poolable.allocatedPermits)
        }

        override suspend fun make(): AnyPoolable = AnyPoolable(maximumPermits = maximumPermits).also {
            poolable = it
        }

        override fun recycle(obj: Any) = Unit
    }

    private class FaultyPoolable(
        nanoTime: () -> Long = System::nanoTime,
        private val error: Throwable? = null
    ) : Poolable<Any>(Any(), nanoTime = nanoTime) {
        override val maximumPermits = 1
        override val isAlive = true
        override val isCanAcquire: Boolean
            get() = allocatedPermits < maximumPermits
        override val isShouldAcquire: Boolean
            get() = isCanAcquire

        override fun isError(throwable: Throwable): Boolean = error?.let { throw it } ?: false
    }

    private class FaultyPoolableFactory(
        private val poolable: FaultyPoolable
    ) : Factory<FaultyPoolable>, Recycler<Any> {
        override val allocations = 1

        override suspend fun close() = Unit

        override suspend fun make() = poolable

        override fun recycle(obj: Any) = Unit
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

    private fun newPool(factory: CloseTrackingFactory) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumPendingAcquisitions = 1_000,
            maximumSize = 1,
            minimumSize = 1,
            reaperDelay = 10L.minutes,
            summaryInterval = 5L.minutes
        ),
        factory,
        factory
    )

    private fun newPool(factory: FaultyPoolableFactory) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumPendingAcquisitions = 1_000,
            maximumSize = 1,
            minimumSize = 1,
            reaperDelay = 10L.minutes,
            summaryInterval = 5L.minutes
        ),
        factory,
        factory
    )

    @Test
    fun closeDrainsAllActivePermitsBeforeClosingFactory() = runTest {
        val factory = CloseTrackingFactory(maximumPermits = 2)
        val pool = newPool(factory)
        val releaseFirstHolder = CompletableDeferred<Unit>()
        val releaseSecondHolder = CompletableDeferred<Unit>()
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val firstHolderStarted = CompletableDeferred<Unit>()
                val firstHolder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        firstHolderStarted.complete(Unit)
                        releaseFirstHolder.await()
                    }
                }
                firstHolderStarted.await()
                val secondHolderStarted = CompletableDeferred<Unit>()
                val secondHolder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        secondHolderStarted.complete(Unit)
                        releaseSecondHolder.await()
                    }
                }
                secondHolderStarted.await()

                val closing = async(start = CoroutineStart.UNDISPATCHED) { pool.close() }
                assertFalse(closing.isCompleted)
                assertFalse(factory.closedPermitCount.isCompleted)
                val failure = assertFailsWith<PoolClosedException> {
                    pool.withPermit(Duration.INFINITE) { }
                }
                assertSame(PoolClosedException, failure)

                releaseFirstHolder.complete(Unit)
                firstHolder.join()
                assertFalse(closing.isCompleted)
                assertFalse(factory.closedPermitCount.isCompleted)

                releaseSecondHolder.complete(Unit)
                secondHolder.join()
                closing.await()
                assertEquals(0, factory.closedPermitCount.await())
            }
        } finally {
            releaseFirstHolder.complete(Unit)
            releaseSecondHolder.complete(Unit)
            pool.close()
        }
    }

    @Test
    fun shutdownContinuesWhenCloseCallerIsCancelled() = runTest {
        val factory = CloseTrackingFactory()
        val pool = newPool(factory)
        val releaseHolder = CompletableDeferred<Unit>()
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderStarted = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderStarted.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderStarted.await()

                val closing = async(start = CoroutineStart.UNDISPATCHED) { pool.close() }
                closing.cancelAndJoin()
                assertFalse(factory.closedPermitCount.isCompleted)

                releaseHolder.complete(Unit)
                holder.join()
                pool.close()
                assertEquals(0, factory.closedPermitCount.await())
            }
        } finally {
            releaseHolder.complete(Unit)
            pool.close()
        }
    }

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

    @Test
    fun errorClassificationFailureDoesNotLeakPermit() = runTest {
        val poolable = FaultyPoolable(error = IllegalStateException("classification failed"))
        val pool = newPool(FaultyPoolableFactory(poolable))
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertFailsWith<IOException> {
                    pool.withPermit(Duration.INFINITE) { throw IOException("request failed") }
                }

                pool.withPermit(5L.seconds) {
                    assertEquals(1, poolable.allocatedPermits)
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun outcomeClockFailureDoesNotLeakPermit() = runTest {
        val poolable = FaultyPoolable(nanoTime = { throw IllegalStateException("clock failed") })
        val pool = newPool(FaultyPoolableFactory(poolable))
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                pool.withPermit(Duration.INFINITE) { }

                pool.withPermit(5L.seconds) {
                    assertEquals(1, poolable.allocatedPermits)
                }
            }
        } finally {
            pool.close()
        }
    }
}
