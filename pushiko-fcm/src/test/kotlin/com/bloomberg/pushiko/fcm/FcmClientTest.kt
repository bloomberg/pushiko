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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.bloomberg.pushiko.fcm

import com.bloomberg.pushiko.exceptions.ClientClosedException
import com.bloomberg.pushiko.fcm.exceptions.FcmException
import com.bloomberg.pushiko.fcm.model.Error
import com.bloomberg.pushiko.fcm.oauth.Session
import com.bloomberg.pushiko.http.HttpClient
import com.bloomberg.pushiko.http.HttpClientProperties.Companion.OptionalHttpProperties
import com.bloomberg.pushiko.http.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer
import java.util.Locale
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val PROJECT_ID = "foo"
private val fakeSendPath = "/v1/projects/%s/messages:send".format(Locale.US, PROJECT_ID)

internal class FcmClientTest {
    private val httpClient = HttpClient {
        host = "fcm.googleapis.com"
        httpProperties = OptionalHttpProperties().copy(
            maximumConnections = 1,
            minimumConnections = 0
        )
    }

    @AfterTest
    fun tearDown(): Unit = runTest {
        httpClient.close()
    }

    /* TODO
    @Test
    fun projectId() {
        val session = mock<Session>().apply {
            whenever(projectId) doReturn "abc"
        }
        assertSame("abc", fcmClientConstructor().call(listOf(session), httpClient, null).projectId)
        verify(session, times(1)).projectId
    }
     */

    @Test
    fun close() = runTest {
        val session = mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "fake"
        }
        val client = fcmClientConstructor().call(mapOf(PROJECT_ID to session), httpClient, null).apply {
            close()
        }
        verifyBlocking(session, times(1)) {
            withContext(Dispatchers.IO) {
                close()
            }
        }
        assertFails {
            client.send(PROJECT_ID, FcmRequest {
                message {
                    token("abc")
                }
            })
        }
    }

    @Test
    fun closeTwice(): Unit = runTest {
        val session = mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "fake"
        }
        val client = fcmClientConstructor().call(mapOf(PROJECT_ID to session), httpClient, null)
        client.close()
        client.close()
    }

    @Test
    fun fcmClientBuilder(): Unit = runTest {
        FcmClient {
            connectionAcquisitionTimeout(1L.minutes)
            maximumConnections(1)
            minimumConnections(0)
        }.run {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                metricsComponent.gauges.read(INFINITE).connectionCount
            }
        }.let {
            assertEquals(0, it)
        }
    }

    @Test
    fun exception(): Unit = runTest {
        val client = fcmClientConstructor().call(mapOf(PROJECT_ID to mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "Bearer xyz"
        }), mock<HttpClient>().apply {
            whenever(send(any())) doThrow RuntimeException()
        }, null)
        assertFailsWith<RuntimeException> { client.send("", FcmRequest { message { token("abc") } }) }
    }

    @Test
    fun closedThenSendException(): Unit = runTest {
        val session = mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "fake"
        }
        val client = fcmClientConstructor().call(mapOf(PROJECT_ID to session), httpClient, null)
        client.close()
        assertFailsWith<ClientClosedException> {
            client.send(PROJECT_ID, FcmRequest { message { token("abc") } })
        }
    }

    @Test
    fun success() = runTest {
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 200
            whenever(body) doReturn Json.encodeToString(FcmSuccessResponse("xyz")).byteInputStream(Charsets.UTF_8)
        }
        val response = fcmClientConstructor().call(mapOf(PROJECT_ID to mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "Bearer xyz"
        }), mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null).send(PROJECT_ID, FcmRequest { message { token("abc") } })
        assertTrue(response is FcmSuccessResponse)
        assertEquals(200, response.code)
        assertEquals("xyz", response.name)
    }

    @Test
    fun clientError(): Unit = runTest {
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 400
            whenever(body) doReturn Json.encodeToString(FcmClientErrorResponse(Error(
                400, "Requested entity was not found.", "NOT_FOUND", null))
            ).byteInputStream(Charsets.UTF_8)
        }
        val response = fcmClientConstructor().call(mapOf(PROJECT_ID to mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "Bearer xyz"
        }), mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null).send(PROJECT_ID, FcmRequest { message { token("abc") } })
        assertTrue(response is FcmClientErrorResponse)
        assertEquals(400, response.error.code)
        assertEquals("Requested entity was not found.", response.error.message)
        assertEquals("NOT_FOUND", response.error.status)
    }

    @Test
    fun serverError500(): Unit = runTest {
        val html = "<!DOCTYPE html></html>"
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 500
            whenever(body) doReturn html.byteInputStream(Charsets.UTF_8)
        }
        val response = fcmClientConstructor().call(mapOf(PROJECT_ID to mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "Bearer xyz"
        }), mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null).send(PROJECT_ID, FcmRequest { message { token("abc") } })
        assertTrue(response is FcmServerErrorResponse)
        assertEquals(500, response.code)
        assertEquals(html, response.body)
    }

    @Test
    fun serverError503RetryAfterSeconds(): Unit = runTest {
        val request = FcmRequest { message { token("abc") } }
        val html = "<!DOCTYPE html></html>"
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 503
            whenever(body) doAnswer { html.byteInputStream(Charsets.UTF_8) }
            whenever(header(eq("retry-after"))) doReturn "30"
        }
        val response = fcmClientConstructor().call(mapOf(PROJECT_ID to mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "Bearer xyz"
        }), mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null).send(PROJECT_ID, request)
        assertTrue(response is FcmServerErrorResponse)
        assertSame(request, response.request)
        assertEquals(503, response.code)
        assertEquals(html, response.body)
        assertEquals(30_000L, response.retryAfterMillis)
    }

    @Test
    fun serverError503RetryAfterDate() = runTest {
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 503
            whenever(header(eq("retry-after"))) doReturn "Tue, 3 Jun 2008 11:05:30 GMT"
        }
        val response = fcmClientConstructor().call(mapOf(PROJECT_ID to mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "Bearer xyz"
        }), mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null).send(PROJECT_ID, FcmRequest { message { token("abc") } })
        assertTrue(response is FcmServerErrorResponse)
        assertEquals(0L, response.retryAfterMillis)
    }

    @Test
    fun serverError503RetryAfterSecondsRetries(): Unit = runTest {
        val request = FcmRequest { message { token("abc") } }
        val serverErrorResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 503
            whenever(body) doReturn "<!DOCTYPE html></html>".byteInputStream(Charsets.UTF_8)
            whenever(header(eq("retry-after"))) doReturn "30"
        }
        val serverSuccessResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 200
            whenever(body) doReturn Json.encodeToString(FcmSuccessResponse("xyz")).byteInputStream(Charsets.UTF_8)
        }
        val response = fcmClientConstructor().call(mapOf(PROJECT_ID to mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "Bearer xyz"
        }), mock<HttpClient>().apply {
            whenever(send(any())) doAnswer object : Answer<HttpResponse> {
                private var count = 0

                override fun answer(invocation: InvocationOnMock) = when (count++) {
                    0 -> serverErrorResponse
                    else -> serverSuccessResponse
                }
            }
        }, null).send(PROJECT_ID, request)
        assertTrue(response is FcmSuccessResponse)
        assertEquals(200, response.code)
    }

    @Test
    fun parseException(): Unit = runTest {
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 404
            whenever(body) doReturn "{".byteInputStream(Charsets.UTF_8)
        }
        val client = fcmClientConstructor().call(mapOf(PROJECT_ID to mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "Bearer xyz"
        }), mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null)
        assertFailsWith<FcmException> {
            client.send(PROJECT_ID, FcmRequest { message { token("abc") } })
        }
    }

    @Test
    fun timeoutRequest(): Unit = runTest {
        val client = fcmClientConstructor().call(mapOf(PROJECT_ID to mock<Session>().apply {
            whenever(sendPath) doReturn fakeSendPath
            whenever(currentAuthorization) doReturn "Bearer xyz"
        }), mock<HttpClient> {
            onBlocking { send(any()) } doSuspendableAnswer {
                delay(1L.minutes)
                fail("Timeout was expected")
            }
        }, null)
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100L.milliseconds) {
                client.send(PROJECT_ID, FcmRequest { message { token("abc") } })
            }
        }
    }

    private fun fcmClientConstructor() = FcmClient::class.constructors.first().apply { isAccessible = true }
}
