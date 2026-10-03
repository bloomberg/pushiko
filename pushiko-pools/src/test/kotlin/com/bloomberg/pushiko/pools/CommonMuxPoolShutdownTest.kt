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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class CommonMuxPoolShutdownTest {
    private class CloseFailingFactory(
        private val failure: RuntimeException
    ) : Factory<AnyPoolable>, Recycler<Any> {
        val closeCalls = AtomicInteger()

        override val allocations = 0

        override suspend fun close() {
            closeCalls.incrementAndGet()
            throw failure
        }

        override suspend fun make() = AnyPoolable()

        override fun recycle(obj: Any) = Unit
    }

    private class BlockingFactory : Factory<AnyPoolable>, Recycler<Any> {
        private val makeStarted = CountDownLatch(1)
        private val releaseMake = CountDownLatch(1)
        private val closeCalled = CountDownLatch(1)

        override val allocations = 0

        override suspend fun close() {
            closeCalled.countDown()
        }

        override suspend fun make(): AnyPoolable {
            makeStarted.countDown()
            releaseMake.await()
            return AnyPoolable()
        }

        override fun recycle(obj: Any) = Unit

        fun release() {
            releaseMake.countDown()
        }

        fun awaitMakeStarted() {
            assertTrue(makeStarted.await(5L, TimeUnit.SECONDS))
        }

        fun awaitClose() {
            assertTrue(closeCalled.await(5L, TimeUnit.SECONDS))
        }
    }

    private class BlockingCloseFailingFactory(
        private val failure: RuntimeException
    ) : Factory<AnyPoolable>, Recycler<Any> {
        private val closeStarted = CompletableDeferred<Unit>()
        private val releaseClose = CompletableDeferred<Unit>()
        val closeCalls = AtomicInteger()

        override val allocations = 0

        override suspend fun close() {
            closeCalls.incrementAndGet()
            closeStarted.complete(Unit)
            releaseClose.await()
            throw failure
        }

        override suspend fun make() = AnyPoolable()

        override fun recycle(obj: Any) = Unit

        suspend fun awaitCloseStarted() {
            closeStarted.await()
        }

        fun release() {
            releaseClose.complete(Unit)
        }
    }

    private fun <P : Poolable<Any>> newPool(
        factory: Factory<P>,
        recycler: Recycler<Any>,
        shutdownTimeout: Duration = 5L.seconds
    ) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumSize = 1,
            minimumSize = 0,
            shutdownTimeout = shutdownTimeout
        ),
        factory,
        recycler
    )

    @Test
    fun closePropagatesFactoryFailureAndRemainsIdempotent() = runTest {
        val failure = IllegalStateException("close failed")
        val factory = CloseFailingFactory(failure)
        val pool = newPool(factory, factory)

        withContext(Dispatchers.Default) {
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { pool.close() }.message)
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { pool.close() }.message)
        }
        assertEquals(1, factory.closeCalls.get())
    }

    @Test
    fun closeTimesOutWhenCreationIgnoresCancellation() = runTest {
        val factory = BlockingFactory()
        val pool = newPool(factory, factory, shutdownTimeout = 100L.milliseconds)
        val acquisition = async(Dispatchers.Default) {
            runCatching { pool.withPermit(Duration.INFINITE) { } }
        }
        withContext(Dispatchers.IO) {
            factory.awaitMakeStarted()
        }

        withContext(Dispatchers.Default) {
            assertFailsWith<TimeoutCancellationException> { pool.close() }
        }

        factory.release()
        withContext(Dispatchers.Default) {
            withTimeout(5L.seconds) {
                pool.close()
            }
        }
        withContext(Dispatchers.IO) {
            factory.awaitClose()
        }
        assertTrue(acquisition.await().isFailure)
    }

    @Test
    fun closeTimeoutDoesNotMaskLaterFactoryFailure() = runTest {
        val failure = IllegalStateException("close failed after timeout")
        val factory = BlockingCloseFailingFactory(failure)
        val pool = newPool(factory, factory, shutdownTimeout = 100L.milliseconds)

        withContext(Dispatchers.Default) {
            val firstClose = async { runCatching { pool.close() } }
            withTimeout(5L.seconds) {
                factory.awaitCloseStarted()
            }
            assertTrue(firstClose.await().exceptionOrNull() is TimeoutCancellationException)

            factory.release()
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { pool.close() }.message)
        }
        assertEquals(1, factory.closeCalls.get())
    }
}
