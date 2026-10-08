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
import com.bloomberg.pushiko.netty.ktx.awaitKt
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.IoEventLoopGroup
import io.netty.channel.IoHandler
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.epoll.EpollIoHandler
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.group.ChannelGroup
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.kqueue.KQueueIoHandler
import io.netty.channel.kqueue.KQueueServerSocketChannel
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.pkitesting.CertificateBuilder
import io.netty.pkitesting.X509Bundle
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT
import io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.SupportedCipherSuiteFilter
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

suspend fun main() {
    FakeHttp2Server(requestedPort = 8443).also {
        Runtime.getRuntime().addShutdownHook(thread(start = false, isDaemon = false) {
            runBlocking {
                it.close()
            }
        })
    }.start()
}

class FakeHttp2Server(
    private val eventLoopGroup: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()),
    private val requestedPort: Int = 0,
    private val maxConcurrentStreams: Long = 100L,
    private val certificate: X509Bundle = selfSignedLocalhostCertificate()
) {
    private val logger = Logger()

    private val dispatcher = eventLoopGroup.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val startDeferred = scope.async(start = CoroutineStart.LAZY) { doStart() }
    private val closedDeferred = scope.async(context = Dispatchers.Default, start = CoroutineStart.LAZY) { doClose() }

    private val sslContext = SslContextBuilder.forServer(certificate.keyPair.private, *certificate.certificatePath)
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

    private lateinit var serverChannel: Channel

    val port: Int
        get() = (serverChannel.localAddress() as InetSocketAddress).port

    suspend fun start() = startDeferred.apply { start() }.await()

    suspend fun close() = closedDeferred.apply { start() }.await()

    private suspend fun doStart() {
        serverChannel = bootstrap.bind(requestedPort).run {
            awaitKt()
            channel()
        }
        logger.info("Server {} has started on port {}", this, port)
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

private fun selfSignedLocalhostCertificate(): X509Bundle = CertificateBuilder()
    .subject("CN=localhost")
    .addSanDnsName("localhost")
    .setIsCertificateAuthority(true)
    .buildSelfSigned()

private fun EventLoopGroup.isIoType(type: Class<out IoHandler>) = (this as? IoEventLoopGroup)?.isIoType(type) == true

@JvmSynthetic
internal fun EventLoopGroup.serverSocketChannelClass() = when {
    isIoType(EpollIoHandler::class.java) -> EpollServerSocketChannel::class.java
    isIoType(KQueueIoHandler::class.java) -> KQueueServerSocketChannel::class.java
    isIoType(NioIoHandler::class.java) -> NioServerSocketChannel::class.java
    else -> error("Unrecognised event loop group: $this")
}.asSubclass(ServerSocketChannel::class.java)
