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

@file:Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.bloomberg.netty.ktx

import io.netty.channel.DefaultChannelPromise
import io.netty.util.concurrent.EventExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

internal class FuturesTest {
    @Test
    fun futureDeferredCompletedSuccess() = runTest {
        val promise = DefaultChannelPromise(mock()).apply {
            assertTrue(trySuccess())
        }
        val deferred = promise.asDeferred()
        assertFalse(deferred.isActive)
        assertFalse(deferred.isCancelled)
        assertTrue(deferred.isCompleted)
        assertNull(deferred.getCompletionExceptionOrNull())
    }

    @Test
    fun awaitDeferredOnFutureCompletedFailure() = runTest {
        val cause = IOException("Uh-oh!")
        val promise = DefaultChannelPromise(mock()).apply {
            assertTrue(tryFailure(cause))
        }
        val deferred = promise.asDeferred()
        try {
            deferred.await()
        } catch (e: IOException) {
            assertEquals(cause.message, e.message)
        }
        assertFalse(deferred.isActive)
        assertTrue(deferred.isCancelled)
        assertTrue(deferred.isCompleted)
        assertSame(cause, deferred.getCompletionExceptionOrNull())
    }

    @Test
    fun awaitDeferredFutureSuccess() = runTest {
        val promise = DefaultChannelPromise(mock(), mock<EventExecutor>().apply {
            whenever(inEventLoop()) doReturn true
        })
        val deferred = promise.asDeferred()
        assertTrue(promise.trySuccess())
        withTimeout(1L.seconds) {
            deferred.await()
        }
    }

    @Test
    fun awaitDeferredFutureFailure() = runTest {
        val promise = DefaultChannelPromise(mock(), mock<EventExecutor>().apply {
            whenever(inEventLoop()) doReturn true
        })
        val deferred = promise.asDeferred()
        val cause = IOException("Uh-oh!")
        assertTrue(promise.tryFailure(cause))
        withTimeout(1L.seconds) {
            try {
                deferred.await()
            } catch (_: IOException) { }
        }
    }

    @Test
    fun awaitDeferredFutureCancelled() = runTest {
        val promise = DefaultChannelPromise(mock(), mock<EventExecutor>().apply {
            whenever(inEventLoop()) doReturn true
        })
        val deferred = promise.asDeferred()
        promise.cancel(false)
        assertTrue(deferred.isCancelled)
        withTimeout(1L.seconds) {
            try {
                deferred.await()
            } catch (_: CancellationException) { }
        }
    }

    @Test
    fun awaitDeferredFutureAlreadyCancelled() = runTest {
        val promise = DefaultChannelPromise(mock(), mock<EventExecutor>().apply {
            whenever(inEventLoop()) doReturn true
        })
        promise.cancel(false)
        val deferred = promise.asDeferred()
        assertTrue(deferred.isCancelled)
        withTimeout(1L.seconds) {
            try {
                deferred.await()
            } catch (_: CancellationException) { }
        }
    }

    @Test
    fun awaitCompletedSuccess() = runTest {
        val promise = DefaultChannelPromise(mock()).apply {
            assertTrue(trySuccess())
        }
        promise.awaitKt()
    }

    @Test
    fun awaitOnFutureAlreadyCompletedFailure() = runTest {
        val cause = IOException("Uh-oh!")
        val promise = DefaultChannelPromise(mock()).apply {
            assertTrue(tryFailure(cause))
        }
        try {
            promise.awaitKt()
        } catch (e: IOException) {
            assertSame(cause, e)
        }
    }

    @Test
    fun awaitFutureAlreadyCancelled() = runTest {
        val promise = DefaultChannelPromise(mock(), mock<EventExecutor>().apply {
            whenever(inEventLoop()) doReturn true
        })
        promise.cancel(false)
        withTimeout(1L.seconds) {
            try {
                promise.awaitKt()
            } catch (_: CancellationException) { }
        }
    }

    @Test
    fun awaitOnFutureFailure() = runTest {
        val cause = IOException("Uh-oh!")
        val promise = DefaultChannelPromise(mock(), mock<EventExecutor>().apply {
            whenever(inEventLoop()) doReturn true
        })
        supervisorScope {
            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                promise.awaitKt()
            }
            assertTrue(promise.tryFailure(cause))
            try {
                deferred.await()
            } catch (e: IOException) {
                assertEquals(cause.message, e.message)
                return@supervisorScope
            }
            fail("Expected exception not thrown")
        }
    }

    @Test
    fun awaitOnFutureCancelled() = runTest {
        val promise = DefaultChannelPromise(mock(), mock<EventExecutor>().apply {
            whenever(inEventLoop()) doReturn true
        })
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            promise.awaitKt()
        }
        promise.cancel(false)
        try {
            deferred.await()
        } catch (_: CancellationException) { }
    }

    @Test
    fun promptCancellation() = runTest {
        val promise = DefaultChannelPromise(mock(), mock<EventExecutor>().apply {
            whenever(inEventLoop()) doReturn true
        })
        val job = Job()
        val deferred = async(context = job, start = CoroutineStart.UNDISPATCHED) {
            promise.awaitKt()
            fail("Await will be expected to throw")
        }
        job.cancel()
        assertTrue(deferred.isCancelled)
        assertTrue(promise.isCancelled, "Expected promise to be cancelled.")
        assertTrue(promise.isDone)
        assertFalse(promise.isSuccess)
        runCatching {
            deferred.await()
        }.onFailure {
            if (it !is CancellationException) {
                fail("Await is expected to throw a CancellationException, instead threw $it")
            }
        }.onSuccess {
            fail("Await is expected to throw")
        }
    }
}
