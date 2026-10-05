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

import com.bloomberg.pushiko.pools.exceptions.PendingAcquisitionLimitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolPendingTest {
    private class DrainingPoolable : Poolable<Any>(Any()) {
        @Volatile
        var isDraining = false
            set(value) {
                field = value
                notifyAvailabilityChanged()
            }

        override val maximumPermits: Int = 1
        override val isAlive: Boolean
            get() = !isDraining
        override val isCanAcquire: Boolean
            get() = !isDraining && allocatedPermits < maximumPermits
        override val isShouldAcquire: Boolean
            get() = isCanAcquire
    }

    private class DrainingPoolableFactory : Factory<DrainingPoolable>, Recycler<Any> {
        private val allocationCount = AtomicInteger()
        private val peakAllocationCount = AtomicInteger()

        val poolables = CopyOnWriteArrayList<DrainingPoolable>()

        lateinit var latest: DrainingPoolable
            private set

        override val allocations: Int
            get() = allocationCount.get()

        val peakAllocations: Int
            get() = peakAllocationCount.get()

        override suspend fun close() = Unit

        override suspend fun make(): DrainingPoolable {
            val allocations = allocationCount.incrementAndGet()
            peakAllocationCount.getAndUpdate { maxOf(it, allocations) }
            return DrainingPoolable().also {
                latest = it
                poolables += it
            }
        }

        override fun recycle(obj: Any) {
            allocationCount.decrementAndGet()
        }
    }

    private fun newDrainingPool(factory: DrainingPoolableFactory) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumPendingAcquisitions = 4,
            maximumSize = 1,
            minimumSize = 1,
            reaperDelay = 10L.minutes,
            summaryInterval = 5L.minutes
        ),
        factory,
        factory
    )

    private class SinglePermitFactory : Factory<AnyPoolable>, Recycler<Any> {
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

    private class DynamicPoolable(initialPermits: Int = 1) : Poolable<Any>(Any()) {
        private var permits = initialPermits

        override val maximumPermits: Int
            get() = permits

        override val isAlive = true

        override val isCanAcquire: Boolean
            get() = allocatedPermits < permits

        override val isShouldAcquire: Boolean
            get() = isCanAcquire

        fun setMaximumPermits(value: Int, notify: Boolean = true) {
            permits = value
            if (notify) {
                notifyAvailabilityChanged()
            }
        }
    }

    private class DynamicPoolableFactory(initialPermits: Int = 1) : Factory<DynamicPoolable>, Recycler<Any> {
        val poolable = DynamicPoolable(initialPermits)

        override val allocations = 1

        override suspend fun close() = Unit

        override suspend fun make() = poolable

        override fun recycle(obj: Any) = Unit
    }

    private fun newPool(
        factory: SinglePermitFactory,
        maximumPendingAcquisitions: Int
    ) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumPendingAcquisitions = maximumPendingAcquisitions,
            maximumSize = 1,
            minimumSize = 1,
            reaperDelay = 10L.minutes,
            summaryInterval = 5L.minutes
        ),
        factory,
        factory
    )

    private fun newDynamicPool(factory: DynamicPoolableFactory) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumPendingAcquisitions = 4,
            maximumSize = 1,
            minimumSize = 1,
            reaperDelay = 10L.minutes,
            summaryInterval = 5L.minutes
        ),
        factory,
        factory
    )

    @Test
    fun rejectsNewestAcquisitionWhenPendingLimitReached() = runTest {
        val factory = SinglePermitFactory()
        val pool = newPool(factory, maximumPendingAcquisitions = 1)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderStarted = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderStarted.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderStarted.await()

                val first = async { runCatching { pool.withPermit(Duration.INFINITE) { } } }
                while (pool.pendingAcquisitionCount() == 0) {
                    yield()
                }

                val second = async { runCatching { pool.withPermit(Duration.INFINITE) { } } }
                val secondResult = second.await()
                assertTrue(secondResult.isFailure)
                assertSame(PendingAcquisitionLimitException, secondResult.exceptionOrNull())
                assertFalse(first.isCompleted)
                assertEquals(1, pool.pendingAcquisitionCount())

                releaseHolder.complete(Unit)
                assertTrue(first.await().isSuccess)
                holder.join()
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun pendingAcquisitionIsServedWhenCapacityFrees() = runTest {
        val factory = SinglePermitFactory()
        val pool = newPool(factory, maximumPendingAcquisitions = 4)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderStarted = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderStarted.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderStarted.await()

                val waiter = async { runCatching { pool.withPermit(Duration.INFINITE) { } } }
                while (pool.pendingAcquisitionCount() == 0) {
                    yield()
                }

                releaseHolder.complete(Unit)
                assertTrue(waiter.await().isSuccess)
                holder.join()
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun pendingAcquisitionIsServedWhenHeldPoolableStartsDraining() = runTest {
        val factory = DrainingPoolableFactory()
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 4,
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
                val holderStarted = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderStarted.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderStarted.await()
                val draining = factory.latest

                val waiter = async { pool.withPermit(Duration.INFINITE) { } }
                while (pool.pendingAcquisitionCount() == 0) {
                    yield()
                }

                draining.isDraining = true

                withTimeout(5L.seconds) {
                    waiter.await()
                }
                assertEquals(1, draining.allocatedPermits)
                assertEquals(2, factory.allocations)

                releaseHolder.complete(Unit)
                holder.join()
                withTimeout(5L.seconds) {
                    while (factory.allocations != 1) {
                        yield()
                    }
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun repeatedDrainingWaitsForPhysicalHeadroomBeforeReplacingAgain() = runTest {
        val factory = DrainingPoolableFactory()
        val pool = newDrainingPool(factory)
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val firstEntered = CompletableDeferred<Unit>()
                val first = launch {
                    pool.withPermit(Duration.INFINITE) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }
                firstEntered.await()
                val firstPoolable = factory.poolables.single()
                firstPoolable.isDraining = true

                withTimeout(60L.seconds) {
                    while (factory.allocations != 2) {
                        yield()
                    }
                }
                val secondPoolable = factory.latest
                val secondEntered = CompletableDeferred<Unit>()
                val second = launch {
                    pool.withPermit(Duration.INFINITE) {
                        secondEntered.complete(Unit)
                        releaseSecond.await()
                    }
                }
                secondEntered.await()
                secondPoolable.isDraining = true

                val waiter = async { pool.withPermit(Duration.INFINITE) { } }
                while (pool.pendingAcquisitionCount() == 0) {
                    yield()
                }
                assertFalse(waiter.isCompleted)
                assertEquals(2, factory.allocations)
                assertEquals(2, factory.peakAllocations)
                assertEquals(2, pool.metricsComponent.gauges(5L.seconds).allocatedSize)

                releaseFirst.complete(Unit)
                first.join()
                withTimeout(60L.seconds) {
                    waiter.await()
                }
                assertEquals(2, factory.peakAllocations)

                releaseSecond.complete(Unit)
                second.join()
                withTimeout(60L.seconds) {
                    while (factory.allocations != 1) {
                        yield()
                    }
                }
            }
        } finally {
            releaseFirst.complete(Unit)
            releaseSecond.complete(Unit)
            pool.close()
        }
    }

    @Test
    fun prunesPendingAcquisitionAfterTimeout() = runTest {
        val factory = SinglePermitFactory()
        val pool = newPool(factory, maximumPendingAcquisitions = 10)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderStarted = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderStarted.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderStarted.await()

                assertFailsWith<TimeoutCancellationException> {
                    pool.withPermit(200L.milliseconds) { }
                }

                var observed: Int
                withTimeout(5L.seconds) {
                    while (pool.pendingAcquisitionCount().also { observed = it } != 0) {
                        yield()
                    }
                }
                assertEquals(0, observed)

                releaseHolder.complete(Unit)
                holder.join()
                pool.withPermit(Duration.INFINITE) { }
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun cancelledNonHeadAcquisitionDoesNotConsumeQueueCapacity() = runTest {
        val factory = SinglePermitFactory()
        val pool = newPool(factory, maximumPendingAcquisitions = 2)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderStarted = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderStarted.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderStarted.await()

                val first = async { runCatching { pool.withPermit(Duration.INFINITE) { } } }
                while (pool.pendingAcquisitionCount() != 1) {
                    yield()
                }

                val cancelled = async { runCatching { pool.withPermit(200L.milliseconds) { } } }
                assertTrue(cancelled.await().exceptionOrNull() is TimeoutCancellationException)
                withTimeout(5L.seconds) {
                    while (pool.pendingAcquisitionCount() != 1) {
                        yield()
                    }
                }

                val last = async { runCatching { pool.withPermit(Duration.INFINITE) { } } }
                while (pool.pendingAcquisitionCount() != 2) {
                    yield()
                }
                assertFalse(first.isCompleted)

                releaseHolder.complete(Unit)
                assertTrue(first.await().isSuccess)
                assertTrue(last.await().isSuccess)
                holder.join()
                assertEquals(0, pool.pendingAcquisitionCount())
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun availabilityChangeResumesAllWaitersForNewCapacity() = runTest {
        val factory = DynamicPoolableFactory()
        val pool = newDynamicPool(factory)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderEntered = CompletableDeferred<Unit>()
                val firstEntered = CompletableDeferred<Unit>()
                val secondEntered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderEntered.complete(Unit)
                        release.await()
                    }
                }
                holderEntered.await()
                val first = launch {
                    pool.withPermit(Duration.INFINITE) {
                        firstEntered.complete(Unit)
                        release.await()
                    }
                }
                val second = launch {
                    pool.withPermit(Duration.INFINITE) {
                        secondEntered.complete(Unit)
                        release.await()
                    }
                }
                while (pool.pendingAcquisitionCount() != 2) {
                    yield()
                }

                factory.poolable.setMaximumPermits(3)

                try {
                    withTimeout(5L.seconds) {
                        firstEntered.await()
                        secondEntered.await()
                    }
                } finally {
                    release.complete(Unit)
                    holder.join()
                    first.join()
                    second.join()
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun initiallyZeroCapacityPoolableRecoversWhenCapacityIncreases() = runTest {
        val factory = DynamicPoolableFactory(initialPermits = 0)
        val pool = newDynamicPool(factory)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                val acquisition = async {
                    pool.withPermit(5L.seconds) { }
                }
                while (pool.pendingAcquisitionCount() != 1) {
                    yield()
                }
                withTimeout(5L.seconds) {
                    while (pool.metricsComponent.gauges(5L.seconds).allocatedSize != 1) {
                        yield()
                    }
                }

                factory.poolable.setMaximumPermits(1)

                withTimeout(5L.seconds) {
                    acquisition.await()
                }
                assertEquals(0, pool.pendingAcquisitionCount())
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun cancelledWaiterHandsAvailableCapacityToNextWaiter() = runTest {
        val factory = DynamicPoolableFactory()
        val pool = newDynamicPool(factory)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderEntered = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderEntered.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderEntered.await()
                val first = async {
                    runCatching {
                        pool.withPermit(200L.milliseconds) { }
                    }
                }
                val second = async {
                    pool.withPermit(Duration.INFINITE) { }
                }
                while (pool.pendingAcquisitionCount() != 2) {
                    yield()
                }

                try {
                    factory.poolable.setMaximumPermits(2, notify = false)
                    assertTrue(first.await().exceptionOrNull() is TimeoutCancellationException)

                    withTimeout(5L.seconds) {
                        second.await()
                    }
                } finally {
                    releaseHolder.complete(Unit)
                    holder.join()
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun callerCancellationRemovesPendingAcquisitionPromptly() = runTest {
        val factory = SinglePermitFactory()
        val pool = newPool(factory, maximumPendingAcquisitions = 10)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val holderStarted = CompletableDeferred<Unit>()
                val releaseHolder = CompletableDeferred<Unit>()
                val holder = launch {
                    pool.withPermit(Duration.INFINITE) {
                        holderStarted.complete(Unit)
                        releaseHolder.await()
                    }
                }
                holderStarted.await()

                val waiter = launch {
                    pool.withPermit(Duration.INFINITE) { }
                }
                while (pool.pendingAcquisitionCount() == 0) {
                    yield()
                }

                withTimeout(5L.seconds) {
                    waiter.cancelAndJoin()
                    while (pool.pendingAcquisitionCount() != 0) {
                        yield()
                    }
                }

                releaseHolder.complete(Unit)
                holder.join()
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }
}
