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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolFactoryFailureTest {
    private class FailThenSuspendFactory : Factory<AnyPoolable>, Recycler<Any> {
        private var callCount = 0
        private var _allocations = 0
        private val secondMakeStarted = CompletableDeferred<Unit>()
        private val allowSecondMake = CompletableDeferred<Unit>()

        override val allocations: Int
            get() = _allocations

        suspend fun awaitSecondMake() {
            secondMakeStarted.await()
        }

        fun releaseSecondMake() {
            allowSecondMake.complete(Unit)
        }

        override suspend fun close() = Unit

        override suspend fun make(): AnyPoolable = when (++callCount) {
            1 -> throw IOException("Simulated connection failure")
            else -> {
                secondMakeStarted.complete(Unit)
                allowSecondMake.await()
                ++_allocations
                AnyPoolable()
            }
        }

        override fun recycle(obj: Any) {
            --_allocations
        }
    }

    private class ThrowingFactory(
        @Volatile private var failuresRemaining: Int,
        private val maximumPermits: Int = 10,
        private val gateFirstMake: Boolean = false
    ) : Factory<AnyPoolable>, Recycler<Any> {
        private var _allocations = 0
        private val makeCallCount = AtomicInteger()
        private val firstMakeStarted = CompletableDeferred<Unit>()
        private val firstMakeGate = CompletableDeferred<Unit>()

        var recyclingCount = 0
            private set

        override val allocations: Int
            get() = _allocations

        val makeCalls: Int
            get() = makeCallCount.get()

        fun recover() {
            failuresRemaining = 0
        }

        suspend fun awaitFirstMake() {
            firstMakeStarted.await()
        }

        fun releaseFirstMake() {
            firstMakeGate.complete(Unit)
        }

        override suspend fun close() = Unit

        override suspend fun make(): AnyPoolable {
            val call = makeCallCount.incrementAndGet()
            if (call == 1) {
                firstMakeStarted.complete(Unit)
                if (gateFirstMake) {
                    firstMakeGate.await()
                }
            }
            if (failuresRemaining > 0) {
                --failuresRemaining
                throw IOException("Simulated connection failure")
            }
            ++_allocations
            return AnyPoolable(maximumPermits = maximumPermits)
        }

        override fun recycle(obj: Any) {
            --_allocations
            ++recyclingCount
        }
    }

    private fun newPool(
        factory: ThrowingFactory,
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

    private fun newPool(
        factory: FailThenSuspendFactory,
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
    fun prepareWaitsForSuspendedSiblingAfterAnotherCreationFails() = runTest {
        val factory = FailThenSuspendFactory()
        val pool = newPool(factory, minimumSize = 2, maximumSize = 2)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                val preparation = async { pool.prepare() }
                withTimeout(5L.seconds) { factory.awaitSecondMake() }

                assertFalse(preparation.isCompleted)

                factory.releaseSecondMake()
                assertEquals(1, preparation.await())
                assertEquals(1, pool.allocatedSize())
            }
        } finally {
            factory.releaseSecondMake()
            pool.close()
        }
    }

    @Test
    fun closingPoolCancelsStructuredFillWithSuspendedSibling() = runTest {
        val factory = FailThenSuspendFactory()
        val pool = newPool(factory, minimumSize = 2, maximumSize = 2)
        var closed = false
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                val preparation = async { runCatching { pool.prepare() } }
                withTimeout(5L.seconds) { factory.awaitSecondMake() }

                pool.close()
                closed = true

                assertSame(PoolClosedException, preparation.await().exceptionOrNull())
                assertEquals(0, pool.allocatedSize())
            }
        } finally {
            factory.releaseSecondMake()
            if (!closed) {
                pool.close()
            }
        }
    }

    @Test
    fun acquisitionFailsPromptlyAndPoolRecoversAfterTransientFactoryFailure() = runTest {
        val factory = ThrowingFactory(failuresRemaining = 1)
        val pool = newPool(factory, minimumSize = 0, maximumSize = 1)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                assertEquals(0, pool.prepare())
                assertFailsWith<IOException> {
                    pool.withPermit(5L.seconds) { }
                }
                pool.withPermit(5L.seconds) { }
                assertEquals(1, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun rejectsAndRecyclesPoolableWithNonPositivePermitCapacity() = runTest {
        listOf(-1, 0).forEach { maximumPermits ->
            val factory = ThrowingFactory(failuresRemaining = 0, maximumPermits = maximumPermits)
            val pool = newPool(factory, minimumSize = 1, maximumSize = 1)
            try {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    assertEquals(0, pool.prepare())
                    assertEquals(0, pool.allocatedSize())
                    assertEquals(0, factory.allocations)
                    assertEquals(1, factory.recyclingCount)
                }
            } finally {
                pool.close()
            }
        }
    }

    @Test
    fun creationFailuresMakeBoundedProgressThroughPendingAcquisitions() = runTest {
        val factory = ThrowingFactory(failuresRemaining = Int.MAX_VALUE, gateFirstMake = true)
        val pool = newPool(factory, minimumSize = 0, maximumSize = 1)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                val acquisitions = List(3) {
                    async {
                        runCatching {
                            pool.withPermit(5L.seconds) { }
                        }
                    }
                }
                factory.awaitFirstMake()
                while (pool.pendingAcquisitionCount() < acquisitions.size) {
                    yield()
                }

                factory.releaseFirstMake()
                acquisitions.awaitAll().forEach {
                    assertFailsWith<IOException> { it.getOrThrow() }
                }
                assertEquals(acquisitions.size, factory.makeCalls)
                assertEquals(0, pool.pendingAcquisitionCount())
            }
        } finally {
            factory.releaseFirstMake()
            pool.close()
        }
    }

    @Test
    fun minimumFillFailureFailsPendingAcquisition() = runTest {
        val factory = ThrowingFactory(failuresRemaining = 2)
        val pool = newPool(factory, minimumSize = 1, maximumSize = 1)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                assertFailsWith<IOException> {
                    pool.withPermit(5L.seconds) { }
                }
                assertEquals(1, factory.makeCalls)
                assertEquals(0, pool.pendingAcquisitionCount())
            }
        } finally {
            pool.close()
        }
    }
}
