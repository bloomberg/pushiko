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

package com.bloomberg.pushiko.http

import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.pushiko.http.HttpClientProperties.Companion.OptionalHttpProperties
import com.bloomberg.pushiko.http.HttpClientProperties.Companion.logger
import com.bloomberg.pushiko.server.FakeHttp2Server
import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val threads = maxOf(1, Runtime.getRuntime().availableProcessors() / 2)

@Suppress("FunctionName")
private fun DefaultEventLoopGroup() = when {
    Epoll.isAvailable() -> EpollEventLoopGroup(threads)
    KQueue.isAvailable() -> KQueueEventLoopGroup(threads)
    else -> NioEventLoopGroup(threads)
}.also {
    logger.info("Shared event loop group {} has {} executors", it, it.executorCount())
}

@ResourceLock("SERVER", mode = ResourceAccessMode.READ_WRITE)
internal class HttpClientTest {
    internal companion object {
        private val logger = Logger()
        private val clientEventLoopGroup: EventLoopGroup by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            DefaultEventLoopGroup()
        }
        private val serverEventLoopGroup: EventLoopGroup by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            DefaultEventLoopGroup()
        }
        private val server = FakeHttp2Server(serverEventLoopGroup)

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
                try {
                    server.close()
                } finally {
                    clientEventLoopGroup.shutdownGracefully().await()
                    serverEventLoopGroup.shutdownGracefully().await()
                }
            }
        }
    }

    private val sslContext = SslContextBuilder.forClient()
        .sslProvider(SslProvider.OPENSSL)
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .applicationProtocolConfig(ApplicationProtocolConfig(ALPN, NO_ADVERTISE, ACCEPT, HTTP_2))
        .build()

    private val client = HttpClient(
        InetSocketAddress.createUnresolved("localhost", 8443),
        sslContext,
        clientEventLoopGroup,
        properties = OptionalHttpProperties().copy(
            isMonitorConnections = true,
            maximumConnections = threads,
            minimumConnections = threads
        )
    )

    private val tasks = listOf(
        WeightedTask(2_000) {
            send(HttpRequest {
                authority("localhost")
                path("/ok")
            })
        },
        WeightedTask(10) { delay(50L) },
        WeightedTask(20) {
            send(HttpRequest {
                authority("localhost")
                path("/sleep/1")
            })
        },
        WeightedTask(5) {
            send(HttpRequest {
                authority("localhost")
                path("/sleep/5")
            })
        },
        WeightedTask(2) {
            send(HttpRequest {
                authority("localhost")
                path("/sleep/10")
            })
        },
        WeightedTask(1) {
            send(HttpRequest {
                authority("localhost")
                path("/silence")
            })
        },
        WeightedTask(1) {
            send(HttpRequest {
                authority("localhost")
                path("/crash")
            })
        }
    )

    @AfterEach
    fun tearDown() = runBlocking {
        client.close()
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.HOURS)
    fun churn(): Unit = runBlocking {
        coroutineScope {
            val count = AtomicInteger(0)
            List(4) {
                async(Dispatchers.Default) {
                    repeat(100_000) {
                        runCatching {
                            tasks.randomTask()(client)
                        }.onFailure {
                            when (it) {
                                is IOException, is TimeoutCancellationException -> logger.info(it.message)
                                else -> throw it
                            }
                        }
                        count.incrementAndGet().let {
                            if (it.mod(100) == 0) {
                                logger.info("Completed $it iterations")
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
