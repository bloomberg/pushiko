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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.bloomberg.pushiko.apns

import com.bloomberg.pushiko.api.exceptions.ClientClosedException
import com.bloomberg.pushiko.http.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.reflect.jvm.isAccessible
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ApnsClientLifecycleTest {
    private val request = ApnsRequest {
        topic("com.foo")
        deviceToken("fake")
    }

    private fun client(http: HttpClient, dispatcher: CoroutineDispatcher): ApnsClient =
        ApnsClient::class.constructors.first().apply { isAccessible = true }.call(http, dispatcher, null)

    private fun ApnsClient.future(method: String): CompletableFuture<*> =
        javaClass.getMethod(method).invoke(this) as CompletableFuture<*>

    private fun ApnsClient.sendFuture(): CompletableFuture<*> = javaClass
        .getMethod("sendFuture", ApnsRequest::class.java).invoke(this, request) as CompletableFuture<*>

    private fun ApnsClient.rootJob(): Job {
        val scope = javaClass.getDeclaredField("javaScope").apply { isAccessible = true }.get(this) as CoroutineScope
        return generateSequence(scope.coroutineContext[Job]!!) { it.parent }.last()
    }

    @Test
    fun closeCancelsFutureSendBeforeClosingHttp() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val http = mock<HttpClient> {
            onBlocking { send(any()) } doSuspendableAnswer {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            onBlocking { close() } doSuspendableAnswer { assertTrue(cancelled.isCompleted) }
        }
        val client = client(http, StandardTestDispatcher(testScheduler))
        val root = client.rootJob()
        val pending = client.sendFuture()
        try {
            runCurrent()
            assertFalse(pending.isDone)
            client.close()
            assertTrue(pending.isCancelled)
            assertTrue(root.isCompleted)
        } finally {
            pending.cancel(false)
            client.close()
        }
    }

    @Test
    fun closeCancelsKotlinSend() = runTest {
        val http = mock<HttpClient> {
            onBlocking { send(any()) } doSuspendableAnswer { awaitCancellation() }
        }
        val client = client(http, StandardTestDispatcher(testScheduler))
        val pending = async { client.send(request) }
        try {
            runCurrent()
            client.close()
            assertTrue(pending.isCancelled)
        } finally {
            pending.cancelAndJoin()
            client.close()
        }
    }

    @Test
    fun closeFutureCancelsStartupAndRemainsIdempotent() = runTest {
        val http = mock<HttpClient> {
            onBlocking { prepare() } doSuspendableAnswer { awaitCancellation() }
        }
        val client = client(http, StandardTestDispatcher(testScheduler))
        val root = client.rootJob()
        val startup = client.future("joinStartFuture")
        try {
            runCurrent()
            val closing = client.future("closeFuture")
            runCurrent()
            assertTrue(closing.isDone)
            closing.join()
            assertTrue(startup.isCancelled)
            assertTrue(root.isCompleted)
            client.future("closeFuture").join()
            client.close()
            verify(http, times(1)).close()
            assertFailsWith<ClientClosedException> { client.send(request) }
        } finally {
            startup.cancel(false)
            client.close()
        }
    }

    @Test
    fun cancellingCloseFutureDoesNotCancelShutdown() = runTest {
        val release = CompletableDeferred<Unit>()
        val http = mock<HttpClient> {
            onBlocking { close() } doSuspendableAnswer { release.await() }
        }
        val client = client(http, StandardTestDispatcher(testScheduler))
        val root = client.rootJob()
        val closing = client.future("closeFuture")
        runCurrent()
        assertFalse(closing.isDone)
        assertFailsWith<ClientClosedException> { client.send(request) }
        assertTrue(client.sendFuture().isCancelled)
        closing.cancel(false)
        release.complete(Unit)
        client.close()
        assertTrue(root.isCompleted)
        verify(http, times(1)).close()
    }

    @Test
    fun shutdownFailureIsStableAcrossCloseCalls() = runTest {
        val failure = IllegalStateException("close failed")
        val http = mock<HttpClient> {
            onBlocking { close() } doSuspendableAnswer { throw failure }
        }
        val client = client(http, StandardTestDispatcher(testScheduler))
        val root = client.rootJob()
        val closing = client.future("closeFuture")
        runCurrent()
        assertTrue(closing.isDone)
        assertEquals(failure.message, assertFailsWith<CompletionException> { closing.join() }.cause?.message)
        assertEquals(failure.message, assertFailsWith<IllegalStateException> { client.close() }.message)
        assertTrue(root.isCompleted)
        verify(http, times(1)).close()
    }

    @Test
    fun futureCancellationStopsOperationWithoutClosingClient() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val http = mock<HttpClient> {
            onBlocking { send(any()) } doSuspendableAnswer {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val client = client(http, StandardTestDispatcher(testScheduler))
        val root = client.rootJob()
        try {
            val pending = client.sendFuture()
            runCurrent()
            pending.cancel(false)
            runCurrent()
            assertTrue(cancelled.isCompleted)
            assertTrue(root.isActive)
            client.joinStart()
        } finally {
            client.close()
        }
    }
}
