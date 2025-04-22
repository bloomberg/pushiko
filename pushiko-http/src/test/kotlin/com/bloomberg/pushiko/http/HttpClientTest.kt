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

package com.bloomberg.pushiko.http

import com.bloomberg.pushiko.health.Status
import com.bloomberg.pushiko.http.HttpClientProperties.Companion.OptionalHttpProperties
import com.bloomberg.pushiko.http.exceptions.HttpClientClosedException
import com.bloomberg.pushiko.http.netty.SharedAllocatorMetric
import com.bloomberg.pushiko.server.FakeHttp2Server
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2Exception
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE
import io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.SupportedCipherSuiteFilter
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@ResourceLock("SERVER", mode = ResourceAccessMode.READ_WRITE)
@Timeout(value = 30L, unit = TimeUnit.SECONDS)
internal class HttpClientTest {
    internal companion object {
        private val server = FakeHttp2Server()
        private val clientProperties = OptionalHttpProperties().copy(
            connectTimeout = 3L.seconds,
            connectionAcquisitionTimeout = 3L.seconds,
            maximumConnectRetries = 2,
            maximumConnections = 1,
            maximumRequestRetries = 0,
            minimumConnections = 1
        )

        @BeforeAll
        @JvmStatic
        fun setUpBeforeAll() {
            runBlocking {
                server.start()
            }
        }

        @AfterAll
        @JvmStatic
        fun tearDownAfterAll() {
            runBlocking {
                server.close()
            }
        }
    }

    private val sslContext = SslContextBuilder.forClient()
        .sslProvider(SslProvider.JDK)
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .applicationProtocolConfig(ApplicationProtocolConfig(ALPN, NO_ADVERTISE, ACCEPT, HTTP_2))
        .build()

    private val eventLoopGroup = NioEventLoopGroup(1)
    private val client = HttpClient(
        InetSocketAddress.createUnresolved("localhost", 8443),
        sslContext,
        eventLoopGroup,
        properties = clientProperties
    )

    @BeforeEach
    fun setUp() = runBlocking {
        client.prepare()
    }

    @AfterEach
    fun tearDown() = runBlocking {
        try {
            client.close()
        } finally {
            eventLoopGroup.shutdownGracefully().sync()
        }
    }

    @Test
    fun close(): Unit = runTest {
        client.close()
        assertFailsWith<HttpClientClosedException> {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                client.send(HttpRequest {
                    authority("localhost")
                })
            }
        }
        assertSame(Status.DOWN, client.healthComponent.connectivity.health(1L.seconds).status)
    }

    @Test
    fun ok(): Unit = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            client.send(HttpRequest {
                authority("localhost")
                path("/ok")
            })
        }.apply {
            assertEquals(200, code)
        }
    }

    @Test
    fun notFound(): Unit = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            client.send(HttpRequest {
                authority("localhost")
                path("/foo")
            })
        }.apply {
            assertEquals(404, code)
        }
    }

    @Test
    fun multipleConcurrentRequests() = runTest {
        val count = AtomicInteger(0)
        withContext(Dispatchers.Default) {
            List(100) {
                launch {
                    repeat(100) {
                        client.send(HttpRequest {
                            authority("localhost")
                            path("/ok")
                        })
                        count.incrementAndGet()
                    }
                }
            }.joinAll()
        }
        assertEquals(10_000, count.get())
        SharedAllocatorMetric.run {
            assertTrue(directArenasCount > 0)
            assertTrue(heapArenasCount > 0)
            assertTrue(pinnedDirectMemory >= 0L)
            assertTrue(pinnedHeapMemory >= 0L)
            assertTrue(threadLocalCachesCount > 0)
            assertTrue(usedDirectMemory > 0L)
            assertTrue(usedHeapMemory > 0L)
            assertTrue(dumpStatistics().isNotBlank())
        }
    }

    @Test
    fun failure(): Unit = runTest {
        HttpClient {
            host = "local"
            port = 8888
            eventLoopGroupType = EventLoopGroupType.SECONDARY
            httpProperties = clientProperties
        }.run {
            try {
                assertFailsWith<TimeoutCancellationException> {
                    withContext(Dispatchers.Default.limitedParallelism(1)) {
                        send(HttpRequest {
                            authority("localhost")
                        })
                    }
                }
            } finally {
                close()
            }
        }
    }

    @Test
    fun healthDown(): Unit = runTest {
        HttpClient {
            host = "local"
            port = 8888
            eventLoopGroupType = EventLoopGroupType.SECONDARY
            httpProperties = clientProperties
        }.run {
            val health = try {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    healthComponent.connectivity.health(1L.seconds)
                }
            } finally {
                close()
            }
            assertSame(Status.DOWN, health.status)
        }
    }

    @Test
    fun promptCancellation(): Unit = runTest {
        HttpClient {
            host = "local"
            port = 8888
            eventLoopGroupType = EventLoopGroupType.SECONDARY
            httpProperties = clientProperties.copy(connectionAcquisitionTimeout = INFINITE)
        }.run {
            val job = Job()
            try {
                assertFailsWith<CancellationException> {
                    withContext(job + Dispatchers.Default.limitedParallelism(1)) {
                        job.cancel()
                        send(HttpRequest {
                            authority("local")
                        })
                    }
                }
            } finally {
                close()
            }
        }
    }

    @Test
    fun promptHealthCancellation(): Unit = runTest {
        HttpClient {
            host = "local"
            port = 8888
            eventLoopGroupType = EventLoopGroupType.SECONDARY
            httpProperties = clientProperties.copy(connectionAcquisitionTimeout = INFINITE)
        }.run {
            val job = Job()
            try {
                assertFailsWith<CancellationException> {
                    withContext(job + Dispatchers.Default.limitedParallelism(1)) {
                        job.cancel()
                        healthComponent.connectivity.health(1L.minutes)
                    }
                }
            } finally {
                close()
            }
        }
    }

    @Test
    fun healthTimeout(): Unit = runTest {
        HttpClient {
            host = "local"
            port = 8888
            eventLoopGroupType = EventLoopGroupType.SECONDARY
            httpProperties = clientProperties.copy(connectionAcquisitionTimeout = INFINITE)
        }.run {
            val health = try {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    healthComponent.connectivity.health(1L.seconds)
                }
            } finally {
                close()
            }
            assertSame(Status.DOWN, health.status)
        }
    }

    @Test
    fun promptMetricsCancellation(): Unit = runTest {
        HttpClient {
            host = "local"
            port = 8888
            eventLoopGroupType = EventLoopGroupType.SECONDARY
            httpProperties = clientProperties.copy(connectionAcquisitionTimeout = INFINITE)
        }.run {
            val job = Job()
            try {
                assertFailsWith<CancellationException> {
                    withContext(job + Dispatchers.Default.limitedParallelism(1)) {
                        job.cancel()
                        metricsComponent.gauges.read(1L.minutes)
                    }
                }
            } finally {
                close()
            }
        }
    }

    @Test
    fun promptTestCancellation(): Unit = runTest {
        HttpClient {
            host = "local"
            port = 8888
            eventLoopGroupType = EventLoopGroupType.SECONDARY
            httpProperties = clientProperties.copy(connectionAcquisitionTimeout = INFINITE)
        }.run {
            val job = Job()
            try {
                assertFailsWith<CancellationException> {
                    withContext(job + Dispatchers.Default.limitedParallelism(1)) {
                        job.cancel()
                        healthComponent.connectivity.health(1L.seconds)
                    }
                }
            } finally {
                close()
            }
        }
    }

    @Test
    fun initialConnectionPool() = runTest {
        client.prepare()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            assertEquals(1, client.metricsComponent.gauges.read(1L.minutes).connectionCount)
            assertSame(Status.UP, client.healthComponent.connectivity.health(1L.minutes).status)
        }
    }

    @Test
    fun connectivity(): Unit = runTest {
        val health = client.run {
            prepare()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                healthComponent.connectivity.health(1L.seconds)
            }
        }
        health.throwable?.let { throw it }
        assertSame(Status.UP, health.status)
    }

    @Test
    fun noConnectivity(): Unit = runTest {
        HttpClient {
            host = "local"
            port = 8888
            eventLoopGroupType = EventLoopGroupType.SECONDARY
            httpProperties = clientProperties
        }.run {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                try {
                    assertSame(Status.DOWN, healthComponent.connectivity.health(1L.seconds).status)
                } finally {
                    close()
                }
            }
        }
    }

    @Test
    fun retryExhausts(): Unit = runTest {
        val client = HttpClient(
            InetSocketAddress.createUnresolved("localhost", 8444),
            sslContext,
            eventLoopGroup,
            properties = clientProperties
        )
        runCatching {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                try {
                    client.send(mock())
                } finally {
                    client.close()
                }
            }
        }.exceptionOrNull()!!.let {
            assert(it is TimeoutCancellationException)
        }
    }

    @Test
    fun isolatedExceptions(): Unit = runTest {
        assertFailsWith<IOException> {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                client.send(HttpRequest {
                    authority("localhost")
                    path("/crash")
                })
            }
        }
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            client.send(HttpRequest {
                authority("localhost")
                path("/ok")
            })
        }.apply {
            assertEquals(200, code)
        }
    }

    @Test
    fun responseTimeoutException(): Unit = runTest(timeout = 60L.seconds) {
        assertFailsWith<SocketTimeoutException> {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                client.send(HttpRequest {
                    authority("localhost")
                    path("/silence")
                })
            }
        }
    }

    @Test
    fun http2Exception(): Unit = runTest {
        val sender = mock<HttpRequestSender> {
            onBlocking { send(any()) } doSuspendableAnswer {
                throw Http2Exception.streamError(1, Http2Error.REFUSED_STREAM, "")
            }
        }
        runCatching {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                HttpClient(sender, clientProperties).send(mock())
            }
        }.exceptionOrNull()!!.let {
            assertTrue(it is IOException)
        }
    }

    @Test
    fun saturation(): Unit = runTest(timeout = 60L.seconds) {
        supervisorScope {
            List(151) {
                async(Dispatchers.IO) {
                    client.send(HttpRequest {
                        authority("localhost")
                        path("/silence")
                    })
                }
            }.run {
                runCatching {
                    awaitAll()
                }.onFailure {
                    if (it is TimeoutCancellationException) {
                        return@run
                    }
                }
                fail("Expected exception not thrown")
            }
        }
    }

    @Test
    fun singleAcquisitionIsHealthy(): Unit = runTest {
        client.prepare()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            assertSame(Status.UP, client.healthComponent.connectivity.health(1L.minutes).status)
        }
    }

    @Test
    fun acquisitionCheckThenSend(): Unit = runTest {
        assertTrue(Runtime.getRuntime().availableProcessors() > 1, "Test requires at least two threads")
        val iterations = 1_000
        client.prepare()
        List(iterations) {
            async(Dispatchers.Default) {
                assertSame(Status.UP, client.healthComponent.connectivity.health(1L.minutes).status)
                client.send(HttpRequest {
                    authority("localhost")
                    path("/ok")
                })
            }
        }.awaitAll()
    }
}
