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

package com.bloomberg.pushiko.server

import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.netty.ktx.awaitKt
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.group.ChannelGroup
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueServerSocketChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT
import io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.SupportedCipherSuiteFilter
import io.netty.handler.ssl.util.SelfSignedCertificate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

suspend fun main() {
    FakeHttp2Server().also {
        Runtime.getRuntime().addShutdownHook(thread(start = false, isDaemon = false) {
            runBlocking {
                it.close()
            }
        })
    }.start()
}

class FakeHttp2Server(
    private val eventLoopGroup: EventLoopGroup = NioEventLoopGroup(1),
    private val port: Int = 8443,
    private val maxConcurrentStreams: Long = 100L
) {
    private val logger = Logger()
    private val certificate = SelfSignedCertificate()

    private val dispatcher = eventLoopGroup.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val startDeferred = scope.async(start = CoroutineStart.LAZY) { doStart() }
    private val closedDeferred = scope.async(context = Dispatchers.Default, start = CoroutineStart.LAZY) { doClose() }

    private val sslContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
        .sslProvider(SslProvider.OPENSSL)
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .applicationProtocolConfig(ApplicationProtocolConfig(ALPN, NO_ADVERTISE, ACCEPT, HTTP_2))
        .build()
    private val bootstrap = ServerBootstrap().apply {
        group(eventLoopGroup)
        channel(eventLoopGroup.serverSocketChannelClass())
        handler(LoggingHandler(LogLevel.DEBUG))
        childHandler(object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(channel: SocketChannel) {
                val sslHandler = sslContext.newHandler(channel.alloc()).apply {
                    handshakeFuture().addListener {
                        if (it.isSuccess) {
                            channels.add(channel)
                        } else {
                            logger.info("TLS handshake failed")
                        }
                    }
                }
                channel.pipeline().addLast(sslHandler, apnHandler())
            }
        })
        option(ChannelOption.SO_BACKLOG, 1_024)
    }
    private val channels: ChannelGroup = DefaultChannelGroup(bootstrap.config().group().next())

    suspend fun start() = startDeferred.apply { start() }.await()

    suspend fun close() = closedDeferred.apply { start() }.await()

    private suspend fun doStart() {
        bootstrap.bind(port).awaitKt()
        logger.info("Server {} has started", this)
    }

    private suspend fun doClose() {
        channels.close().awaitKt()
        eventLoopGroup.shutdownGracefully().sync()
        logger.info("Server {} has stopped", this)
    }

    private fun apnHandler() = object : ApplicationProtocolNegotiationHandler(HTTP_2) {
        override fun configurePipeline(context: ChannelHandlerContext, protocol: String) {
            check(HTTP_2 == protocol) { "Protocol '$protocol' not supported" }
            context.pipeline().addLast(ConnectionHandlerBuilder().maxConcurrentStreams(maxConcurrentStreams).build())
        }
    }
}

@JvmSynthetic
internal fun EventLoopGroup.serverSocketChannelClass() = when (this) {
    is NioEventLoopGroup -> NioServerSocketChannel::class.java
    is EpollEventLoopGroup -> EpollServerSocketChannel::class.java
    is KQueueEventLoopGroup -> KQueueServerSocketChannel::class.java
    else -> error("Unrecognised event loop group: $javaClass")
}.asSubclass(ServerSocketChannel::class.java)
