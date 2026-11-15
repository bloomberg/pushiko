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

package com.bloomberg.pushiko.fcm

import com.bloomberg.pushiko.api.exceptions.ClientClosedException
import com.bloomberg.pushiko.fcm.oauth.Session
import com.bloomberg.pushiko.http.HttpClient
import com.bloomberg.pushiko.http.HttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.reflect.jvm.isAccessible
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class FcmClientLifecycleTest {
    private val request = FcmRequest { message { token("fake") } }
    private val session = mock<Session> {
        on { sendPath } doReturn "/v1/projects/foo/messages:send"
        onBlocking { currentAuthorization() } doReturn "Bearer fake"
    }

    private fun client(http: HttpClient, dispatcher: CoroutineDispatcher): FcmClient =
        FcmClient::class.constructors.first().apply { isAccessible = true }
            .call(mapOf("foo" to session), http, dispatcher, 60_000L)

    private fun retryingHttp(): HttpClient {
        val response = mock<HttpResponse> {
            on { code } doReturn 503
            on { header(any()) } doReturn "60"
            on { body } doReturn "retry later".byteInputStream()
        }
        return mock {
            onBlocking { send(any()) } doReturn response
        }
    }

    private fun FcmClient.future(method: String): CompletableFuture<*> =
        javaClass.getMethod(method).invoke(this) as CompletableFuture<*>

    private fun FcmClient.sendFuture(): CompletableFuture<*> = javaClass
        .getMethod("sendFuture", String::class.java, FcmRequest::class.java)
        .invoke(this, "foo", request) as CompletableFuture<*>

    private fun FcmClient.rootJob(): Job {
        val scope = javaClass.getDeclaredField("javaScope").apply { isAccessible = true }.get(this) as CoroutineScope
        return generateSequence(scope.coroutineContext[Job]!!) { it.parent }.last()
    }

    @Test
    fun closeCancelsFutureRetryAndCompletesRootJob() = runTest {
        val http = retryingHttp()
        val client = client(http, StandardTestDispatcher(testScheduler))
        val root = client.rootJob()
        val pending = client.sendFuture()
        try {
            runCurrent()
            assertFalse(pending.isDone)
            client.close()
            assertTrue(pending.isCancelled)
            assertTrue(root.isCompleted)
            advanceTimeBy(60_000L)
            runCurrent()
            verify(session, times(1)).currentAuthorization()
            verify(http, times(1)).send(any())
        } finally {
            pending.cancel(false)
            client.close()
        }
    }

    @Test
    fun closeCancelsKotlinRetry() = runTest {
        val http = retryingHttp()
        val client = client(http, StandardTestDispatcher(testScheduler))
        val pending = async { client.send("foo", request) }
        try {
            runCurrent()
            client.close()
            assertTrue(pending.isCancelled)
            advanceTimeBy(60_000L)
            runCurrent()
            verify(session, times(1)).currentAuthorization()
            verify(http, times(1)).send(any())
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
            verify(session, times(1)).close()
            assertFailsWith<ClientClosedException> { client.send("foo", request) }
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
        assertFailsWith<ClientClosedException> { client.send("foo", request) }
        assertTrue(client.sendFuture().isCancelled)
        closing.cancel(false)
        release.complete(Unit)
        client.close()
        assertTrue(root.isCompleted)
        verify(http, times(1)).close()
        verify(session, times(1)).close()
    }

    @Test
    fun cancellingCloseCallerDoesNotCancelShutdown() = runTest {
        val release = CompletableDeferred<Unit>()
        val http = mock<HttpClient> {
            onBlocking { close() } doSuspendableAnswer { release.await() }
        }
        val client = client(http, StandardTestDispatcher(testScheduler))
        val root = client.rootJob()
        val closing = async { client.close() }
        runCurrent()
        closing.cancelAndJoin()
        release.complete(Unit)
        client.close()
        assertTrue(root.isCompleted)
        verify(http, times(1)).close()
        verify(session, times(1)).close()
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
        verify(session, times(1)).close()
    }

    @Test
    fun callerCancellationCancelsOperationBeforeReturning() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        whenever(session.currentAuthorization()) doSuspendableAnswer {
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val client = client(mock(), StandardTestDispatcher(testScheduler))
        try {
            val pending = async { client.send("foo", request) }
            runCurrent()
            pending.cancelAndJoin()
            assertTrue(cancelled.isCompleted)
        } finally {
            client.close()
        }
    }
}
