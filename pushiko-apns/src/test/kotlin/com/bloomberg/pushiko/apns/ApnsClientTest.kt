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

package com.bloomberg.pushiko.apns

import com.bloomberg.pushiko.api.exceptions.ClientClosedException
import com.bloomberg.pushiko.apns.exceptions.ApnsException
import com.bloomberg.pushiko.apns.keys.ApnsProviderToken
import com.bloomberg.pushiko.apns.keys.ApnsSigningKey
import com.bloomberg.pushiko.apns.model.Priority
import com.bloomberg.pushiko.apns.model.PushType
import com.bloomberg.pushiko.http.HttpClient
import com.bloomberg.pushiko.http.HttpResponse
import com.bloomberg.pushiko.http.exceptions.HttpClientClosedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

internal class ApnsClientTest {
    private val httpClient = HttpClient {
        host = "api.sandbox.push.apple.com"
    }

    @AfterTest
    fun tearDown(): Unit = runTest {
        httpClient.close()
    }

    @Test
    fun close() = runTest {
        val client = apnsClientConstructor().call(httpClient, null, null)
        client.close()
        assertFails {
            client.send(ApnsRequest {
                topic("com.foo")
                deviceToken("abc123")
            })
        }
    }

    @Test
    fun closeTwice(): Unit = runTest {
        val client = apnsClientConstructor().call(httpClient, null, null)
        client.close()
        client.close()
    }

    @Test
    fun exception(): Unit = runTest {
        val httpClient = mock<HttpClient> {
            onBlocking { send(any()) } doSuspendableAnswer {
                throw RuntimeException()
            }
        }
        val client = apnsClientConstructor().call(httpClient, null, null)
        try {
            assertFailsWith<RuntimeException> {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    client.send(ApnsRequest {
                        topic("com.foo")
                        deviceToken("abc123")
                    })
                }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun closedThenSendException(): Unit = runTest {
        val client = apnsClientConstructor().call(mock<HttpClient>().apply {
            whenever(send(any())) doThrow HttpClientClosedException
        }, null, null)
        client.close()
        assertFailsWith<ClientClosedException> {
            client.send(ApnsRequest {
                topic("com.foo")
                deviceToken("abc123")
            })
        }
    }

    @Test
    fun success() = runTest {
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 200
            whenever(header(eq("apns-id"))) doReturn "foo-id"
        }
        val client = apnsClientConstructor().call(mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null, null)
        val response = try {
            client.send(ApnsRequest {
                topic("com.foo")
                deviceToken("abc123")
            })
        } finally {
            client.close()
        }
        assertTrue(response is ApnsSuccessResponse)
        assertEquals(200, response.code)
        assertEquals("foo-id", response.apnsId)
        assertNull(response.apnsUniqueId)
    }

    @Test
    fun successfulPayload() = runTest {
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 200
            whenever(header(eq("apns-id"))) doReturn "foo-id"
            whenever(header(eq("apns-unique-id"))) doReturn "bar-id"
        }
        val client = apnsClientConstructor().call(mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null, null)
        val response = try {
            client.send(ApnsRequest {
                id(UUID.randomUUID())
                collapseId("collapse-it")
                expiration(Instant.now())
                priority(Priority.CONSERVE_POWER)
                pushType(PushType.BACKGROUND)
                topic("com.foo")
                deviceToken("abc123")
                aps {
                    alert {
                        title("Hello")
                        body("world!")
                    }
                }
            })
        } finally {
            client.close()
        }
        assertTrue(response is ApnsSuccessResponse)
        assertEquals(200, response.code)
        assertEquals("foo-id", response.apnsId)
        assertEquals("bar-id", response.apnsUniqueId)
    }

    @Test
    fun tokenAuthenticationAddsAuthorizationHeader() = runTest {
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 200
            whenever(header(eq("apns-id"))) doReturn "foo-id"
        }
        var authorization: String? = null
        val httpClient = mock<HttpClient> {
            onBlocking { send(any()) } doSuspendableAnswer { invocation ->
                authorization = (invocation.arguments[0] as com.bloomberg.pushiko.http.HttpRequest)
                    .header("authorization")
                httpResponse
            }
        }
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val providerToken = ApnsProviderToken(
            ApnsSigningKey("KEYID12345", "TEAMID1234", keyPair.private as ECPrivateKey),
            Clock.fixed(Instant.parse("2026-09-13T12:34:56Z"), ZoneOffset.UTC)
        )
        val client = apnsClientConstructor().call(httpClient, null, providerToken)
        try {
            client.send(ApnsRequest {
                topic("com.foo")
                deviceToken("abc123")
            })
        } finally {
            client.close()
        }

        assertTrue(authorization?.startsWith("bearer ey") == true)
    }

    @Test
    fun expiredProviderTokenResponseInvalidatesAuthorization() = runTest {
        val responses = listOf(
            mock<HttpResponse>().apply {
                whenever(code) doReturn 403
                whenever(header(eq("apns-id"))) doReturn "first-id"
                whenever(body) doReturn """{"reason":"ExpiredProviderToken"}""".byteInputStream()
            },
            mock<HttpResponse>().apply {
                whenever(code) doReturn 200
                whenever(header(eq("apns-id"))) doReturn "second-id"
            }
        )
        val authorizations = mutableListOf<String?>()
        var responseIndex = 0
        val httpClient = mock<HttpClient> {
            onBlocking { send(any()) } doSuspendableAnswer { invocation ->
                authorizations += (invocation.arguments[0] as com.bloomberg.pushiko.http.HttpRequest)
                    .header("authorization")
                responses[responseIndex++]
            }
        }
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val providerToken = ApnsProviderToken(
            ApnsSigningKey("KEYID12345", "TEAMID1234", keyPair.private as ECPrivateKey),
            Clock.fixed(Instant.parse("2026-09-13T12:34:56Z"), ZoneOffset.UTC)
        )
        val client = apnsClientConstructor().call(httpClient, null, providerToken)
        val request = ApnsRequest {
            topic("com.foo")
            deviceToken("abc123")
        }
        try {
            assertTrue(client.send(request) is ApnsClientErrorResponse)
            assertTrue(client.send(request) is ApnsSuccessResponse)
        } finally {
            client.close()
        }

        assertNotEquals(authorizations[0], authorizations[1])
    }

    @Test
    fun clientError(): Unit = runTest {
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 400
            whenever(header(eq("apns-id"))) doReturn "foo-id"
            whenever(body) doReturn """
                {
                    "reason": "BadDeviceToken"
                }
            """.trimIndent().byteInputStream(Charsets.UTF_8)
        }
        val client = apnsClientConstructor().call(mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null, null)
        val response = try {
            client.send(ApnsRequest {
                topic("com.foo")
                deviceToken("abc123")
            })
        } finally {
            client.close()
        }
        assertTrue(response is ApnsClientErrorResponse)
        assertEquals(400, response.code())
        assertEquals("BadDeviceToken", response.reason)
        assertEquals("foo-id", response.apnsId())
    }

    @Test
    fun serverError500(): Unit = runTest {
        val html = "<!DOCTYPE html></html>"
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 500
            whenever(body) doReturn html.byteInputStream(Charsets.UTF_8)
        }
        val client = apnsClientConstructor().call(mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null, null)
        val response = try {
            client.send(ApnsRequest {
                topic("com.foo")
                deviceToken("abc123")
            })
        } finally {
            client.close()
        }
        assertTrue(response is ApnsServerErrorResponse)
        assertEquals(500, response.code)
        assertEquals(html, response.body)
    }

    @Test
    fun serverError503RetryAfterSeconds(): Unit = runTest {
        val request = ApnsRequest {
            topic("com.foo")
            deviceToken("abc123")
        }
        val html = "<!DOCTYPE html></html>"
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 503
            whenever(body) doReturn html.byteInputStream(Charsets.UTF_8)
            whenever(header(eq("retry-after"))) doReturn "30"
        }
        val client = apnsClientConstructor().call(mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null, null)
        val response = try {
            client.send(request)
        } finally {
            client.close()
        }
        assertTrue(response is ApnsServerErrorResponse)
        assertEquals(503, response.code)
        assertEquals(html, response.body)
    }

    @Test
    fun serverError503RetryAfterDate() = runTest {
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 503
            whenever(header(eq("retry-after"))) doReturn "Tue, 3 Jun 2008 11:05:30 GMT"
        }
        val response = apnsClientConstructor().call(mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null, null).send(ApnsRequest {
            topic("com.foo")
            deviceToken("abc123")
        })
        assertTrue(response is ApnsServerErrorResponse)
    }

    @Test
    fun parseException(): Unit = runTest {
        val httpResponse = mock<HttpResponse>().apply {
            whenever(code) doReturn 404
            whenever(body) doReturn "{".byteInputStream(Charsets.UTF_8)
        }
        val client = apnsClientConstructor().call(mock<HttpClient>().apply {
            whenever(send(any())) doReturn httpResponse
        }, null, null)
        try {
            assertFailsWith<ApnsException> {
                client.send(ApnsRequest {
                    topic("com.foo")
                    deviceToken("abc123")
                })
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun developmentApnsClientBuilder() {
        ApnsClient {
            environment = ApnsEnvironment.DEVELOPMENT
            clientCredentials(
                File(javaClass.classLoader.getResource("keystore.pkcs12")!!.toURI()),
                "changeit".toCharArray()
            )
        }
    }

    @Test
    fun timeoutRequest(): Unit = runTest {
        val client = apnsClientConstructor().call(mock<HttpClient> {
            onBlocking { send(any()) } doSuspendableAnswer {
                delay(1L.minutes)
                fail("Timeout was expected")
            }
        }, null, null)
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100L.milliseconds) {
                client.send(ApnsRequest {
                    topic("com.foo")
                    deviceToken("abc123")
                })
            }
        }
    }

    private fun apnsClientConstructor() = ApnsClient::class.constructors.first().apply { isAccessible = true }

    private fun com.bloomberg.pushiko.http.HttpRequest.header(name: String): String? {
        val headers = javaClass.getDeclaredField("headers").apply { isAccessible = true }.get(this)
        return headers.javaClass.getMethod("get", Any::class.java).invoke(headers, name)?.toString()
    }
}
