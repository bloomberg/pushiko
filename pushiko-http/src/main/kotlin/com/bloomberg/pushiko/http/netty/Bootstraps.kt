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

/*
 * Copyright (c) 2020 Jon Chambers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.bloomberg.pushiko.http.netty

import com.bloomberg.pushiko.commons.slf4j.Logger
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http2.Http2FrameLogger
import io.netty.handler.flush.FlushConsolidationHandler
import io.netty.handler.proxy.ProxyHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.timeout.IdleStateHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

private const val SHUTDOWN_TIMEOUT_MILLIS = 10 * 1_000L
private const val WRITE_TIMEOUT_SECONDS = 5L

@Suppress("LongParameterList") // FIXME
internal suspend fun Bootstrap.pushikoClone(
    proxyHandlerFactory: ProxyHandlerFactory?,
    sslContext: SslContext,
    serverAddress: InetSocketAddress,
    frameLogger: Http2FrameLogger?,
    channelConfiguration: ChannelFactoryConfiguration
) = clone().apply {
    handler(
        PushikoChannelInitializer(
            sslContext,
            proxyHandlerFactory?.createProxyHandler(),
            serverAddress,
            frameLogger,
            channelConfiguration
        )
    )
}

@Suppress("LongParameterList") // FIXME
private class PushikoChannelInitializer(
    private val sslContext: SslContext,
    private val proxyHandler: ProxyHandler?,
    private val serverAddress: InetSocketAddress,
    private val frameLogger: Http2FrameLogger?,
    private val channelConfiguration: ChannelFactoryConfiguration
) : ChannelInitializer<SocketChannel>() {
    private val logger = Logger()

    override fun initChannel(channel: SocketChannel) {
        val sslHandler = sslContext.newHandler(
            channel.alloc(),
            serverAddress.hostName,
            serverAddress.port
        ).apply {
            engine().sslParameters = engine().sslParameters.also {
                it.endpointIdentificationAlgorithm = "HTTPS"
            }
            handshakeFuture().addListener {
                if (it.isSuccess) {
                    logger.info(
                        "{} HANDSHAKEN: transport protocol:{} cipher suite:{} application protocol:{}",
                        it.now, engine().session.protocol, engine().session.cipherSuite, applicationProtocol()
                    )
                }
            }
        }
        channel.pipeline().apply {
            addFirst(proxyHandler)
            addLast(sslHandler)
            addLast(FlushConsolidationHandler(
                FlushConsolidationHandler.DEFAULT_EXPLICIT_FLUSH_AFTER_FLUSHES, true))
            addLast("idle", IdleStateHandler(channelConfiguration.idleInterval.inWholeMilliseconds, 0L, 0L,
                TimeUnit.MILLISECONDS))
            channelConfiguration.maximumAge.takeUnless { it.isInfinite() }?.let {
                addLast("maxAge", MaximumAgeConnectionHandler(it))
            }
            addLast("writeTimeout", WriteTimeoutHandler(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            addLast(ConnectionHandler {
                frameLogger = this@PushikoChannelInitializer.frameLogger
                monitorConnection = this@PushikoChannelInitializer.channelConfiguration.isMonitored
            }.apply {
                gracefulShutdownTimeoutMillis(SHUTDOWN_TIMEOUT_MILLIS)
            })
        }
    }
}
