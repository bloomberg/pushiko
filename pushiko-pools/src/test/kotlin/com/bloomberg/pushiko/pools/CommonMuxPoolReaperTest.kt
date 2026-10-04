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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class CommonMuxPoolReaperTest {
    private class Resource

    private class ResourcePoolable(
        resource: Resource,
        private val onAliveCheck: () -> Unit
    ) : Poolable<Resource>(resource) {
        override val maximumPermits: Int = 1
        override val isAlive: Boolean
            get() {
                onAliveCheck()
                return true
            }
        override val isCanAcquire: Boolean
            get() = allocatedPermits < maximumPermits
        override val isShouldAcquire: Boolean
            get() = isCanAcquire
    }

    private class TrackingFactory(
        private val blockOnRecycleNumber: Int? = null
    ) : Factory<ResourcePoolable>, Recycler<Resource> {
        private val allocationCount = AtomicInteger()
        private val poolables = ConcurrentHashMap<Resource, ResourcePoolable>()
        private val recycleBlocked = CountDownLatch(1)
        private val allowRecycle = CountDownLatch(1)
        private val aliveChecks = AtomicReference<CountDownLatch?>()

        val permitsAtRecycle = CopyOnWriteArrayList<Int>()

        override val allocations: Int
            get() = allocationCount.get()

        override suspend fun close() = Unit

        override suspend fun make(): ResourcePoolable {
            val resource = Resource()
            return ResourcePoolable(resource) {
                aliveChecks.get()?.countDown()
            }.also {
                poolables[resource] = it
                allocationCount.incrementAndGet()
            }
        }

        override fun recycle(obj: Resource) {
            permitsAtRecycle += poolables.getValue(obj).allocatedPermits
            allocationCount.decrementAndGet()
            if (permitsAtRecycle.size == blockOnRecycleNumber) {
                recycleBlocked.countDown()
                check(allowRecycle.await(5L, TimeUnit.SECONDS))
            }
        }

        fun awaitBlockedRecycle() {
            assertTrue(recycleBlocked.await(5L, TimeUnit.SECONDS))
        }

        fun releaseBlockedRecycle() {
            allowRecycle.countDown()
        }

        fun expectAliveChecks(count: Int) {
            check(aliveChecks.compareAndSet(null, CountDownLatch(count)))
        }

        fun awaitAliveChecks() {
            assertTrue(checkNotNull(aliveChecks.get()).await(5L, TimeUnit.SECONDS))
        }
    }

    @Test
    @Suppress("CognitiveComplexMethod", "LongMethod")
    fun reschedulingCancelsActiveReaperPass() = runBlocking {
        val reaperDelay = 500L.milliseconds
        val factory = TrackingFactory(blockOnRecycleNumber = 16)
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 32,
                maximumSize = 32,
                minimumSize = 1,
                reaperDelay = reaperDelay,
                summaryInterval = 5L.minutes
            ),
            factory,
            factory
        )
        val allHeld = CompletableDeferred<Unit>()
        val releaseHolders = CompletableDeferred<Unit>()
        val heldCount = AtomicInteger()
        var workers: List<Job> = emptyList()
        try {
            workers = List(32) {
                launch(Dispatchers.Default) {
                    pool.withPermit(Duration.INFINITE) {
                        if (heldCount.incrementAndGet() == 32) {
                            allHeld.complete(Unit)
                        }
                        releaseHolders.await()
                    }
                }
            }
            withTimeout(5L.seconds) {
                allHeld.await()
            }
            releaseHolders.complete(Unit)
            workers.joinAll()

            withContext(Dispatchers.IO) {
                factory.awaitBlockedRecycle()
            }
            val rescheduling = async(start = CoroutineStart.UNDISPATCHED) {
                pool.rescheduleReaperForTest()
            }
            factory.releaseBlockedRecycle()

            val cancelledReaper = assertNotNull(rescheduling.await())
            cancelledReaper.join()
            assertEquals(16, factory.permitsAtRecycle.size)

            val cancelledReplacement = assertNotNull(pool.rescheduleReaperForTest())
            assertTrue(cancelledReplacement.isCancelled)
            delay(100L.milliseconds)
            assertEquals(16, factory.permitsAtRecycle.size)

            withTimeout(5L.seconds) {
                while (factory.permitsAtRecycle.size < 31) {
                    delay(10L.milliseconds)
                }
            }
            assertEquals(31, factory.permitsAtRecycle.size)
            assertEquals(1, factory.allocations)
        } finally {
            factory.releaseBlockedRecycle()
            releaseHolders.complete(Unit)
            workers.joinAll()
            pool.close()
        }
    }

    @Test
    @Suppress("CognitiveComplexMethod")
    fun reaperDefersPoolablesWithOutstandingPermits() = runBlocking {
        val reaperDelay = 100L.milliseconds
        val factory = TrackingFactory()
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 10,
                maximumSize = 2,
                minimumSize = 0,
                reaperDelay = reaperDelay,
                summaryInterval = 5L.minutes
            ),
            factory,
            factory
        )
        val bothHeld = CompletableDeferred<Unit>()
        val releaseActive = CompletableDeferred<Unit>()
        val heldCount = AtomicInteger()
        var workers: List<Job> = emptyList()
        try {
            workers = List(2) { workerIndex ->
                launch(Dispatchers.Default) {
                    pool.withPermit(Duration.INFINITE) {
                        if (heldCount.incrementAndGet() == 2) {
                            bothHeld.complete(Unit)
                        }
                        bothHeld.await()
                        if (workerIndex == 0) {
                            releaseActive.await()
                        }
                    }
                }
            }
            withTimeout(5L.seconds) {
                bothHeld.await()
            }
            withTimeout(5L.seconds) {
                while (factory.permitsAtRecycle.isEmpty()) {
                    delay(10L.milliseconds)
                }
            }
            assertEquals(listOf(0), factory.permitsAtRecycle)
            assertEquals(1, factory.allocations)

            releaseActive.complete(Unit)
            workers.joinAll()
            withTimeout(5L.seconds) {
                while (factory.permitsAtRecycle.size < 2) {
                    delay(10L.milliseconds)
                }
            }
            assertEquals(listOf(0, 0), factory.permitsAtRecycle)
            assertEquals(0, factory.allocations)
        } finally {
            releaseActive.complete(Unit)
            workers.joinAll()
            pool.close()
        }
    }

    @Test
    fun reaperRetriesWhenAllPoolablesHaveOutstandingPermits() = runBlocking {
        val factory = TrackingFactory()
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 10,
                maximumSize = 2,
                minimumSize = 0,
                reaperDelay = 100L.milliseconds,
                summaryInterval = 5L.minutes
            ),
            factory,
            factory
        )
        val bothHeld = CompletableDeferred<Unit>()
        val releaseHolders = CompletableDeferred<Unit>()
        val heldCount = AtomicInteger()
        var workers: List<Job> = emptyList()
        try {
            workers = List(2) {
                launch(Dispatchers.Default) {
                    pool.withPermit(Duration.INFINITE) {
                        if (heldCount.incrementAndGet() == 2) {
                            bothHeld.complete(Unit)
                        }
                        releaseHolders.await()
                    }
                }
            }
            withTimeout(5L.seconds) {
                bothHeld.await()
            }
            // The start of a second pass proves that the first inspected every poolable without recycling either one.
            factory.expectAliveChecks(4)
            withContext(Dispatchers.IO) {
                factory.awaitAliveChecks()
            }
            assertTrue(factory.permitsAtRecycle.isEmpty())
            assertEquals(2, factory.allocations)

            releaseHolders.complete(Unit)
            workers.joinAll()
            withTimeout(5L.seconds) {
                while (factory.permitsAtRecycle.size < 2) {
                    delay(10L.milliseconds)
                }
            }
            assertEquals(listOf(0, 0), factory.permitsAtRecycle)
            assertEquals(0, factory.allocations)
        } finally {
            releaseHolders.complete(Unit)
            workers.joinAll()
            pool.close()
        }
    }
}
