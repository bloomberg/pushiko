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
import com.bloomberg.pushiko.http.IHttpClientProperties
import com.bloomberg.pushiko.http.exceptions.ChannelInactiveException
import com.bloomberg.netty.ktx.awaitKt
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.ReflectiveChannelFactory
import io.netty.channel.WriteBufferWaterMark
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollChannelOption
import io.netty.channel.group.DefaultChannelGroup
import io.netty.handler.codec.http2.Http2FrameLogger
import io.netty.handler.ssl.SslContext
import io.netty.handler.timeout.TimeoutException
import io.netty.resolver.AddressResolverGroup
import io.netty.resolver.dns.DefaultDnsServerAddressStreamProvider
import io.netty.resolver.dns.RoundRobinDnsAddressResolverGroup
import io.netty.util.AttributeKey
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.Long.min
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ThreadLocalRandom
import javax.annotation.concurrent.ThreadSafe
import kotlin.coroutines.Continuation
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private val CommonWriteBufferWaterMark = WriteBufferWaterMark(128 * 1_024, 512 * 1_024)

internal val channelContinuationAttributeKey = AttributeKey.valueOf<Continuation<Channel>>(
    ChannelFactory::class.java,
    "channelReadyPromise"
)

internal data class ChannelFactoryConfiguration(
    val connectionRetryFuzzInterval: Duration = 500L.toDuration(DurationUnit.MILLISECONDS),
    val idleInterval: Duration,
    val isMonitored: Boolean,
    val maximumAge: Duration,
    val maximumConnectRetryAttempts: Int,
    val maximumConnectRetryDelay: Duration = (10 * 1_000L).toDuration(DurationUnit.MILLISECONDS),
    val minimumConnectRetryDelay: Duration = 500L.toDuration(DurationUnit.MILLISECONDS),
    val tcpUserTimeout: Duration = 10_000L.toDuration(DurationUnit.MILLISECONDS)
)

@ThreadSafe
internal class ChannelFactory(
    private val serverAddress: InetSocketAddress,
    private val sslContext: SslContext,
    eventLoopGroup: EventLoopGroup,
    private val httpProperties: IHttpClientProperties,
    private val frameLogger: Http2FrameLogger?,
    private val configuration: ChannelFactoryConfiguration
) {
    private val logger = Logger()
    private val allChannels = DefaultChannelGroup(eventLoopGroup.next(), true)

    private val addressResolverGroup: AddressResolverGroup<out SocketAddress> = RoundRobinDnsAddressResolverGroup(
        eventLoopGroup.javaClass.canonicalName.datagramChannelClass(), DefaultDnsServerAddressStreamProvider.INSTANCE)
    private val proxyHandlerFactory: ProxyHandlerFactory? = httpProperties.unresolvedProxyAddress?.let {
        val eventLoop = eventLoopGroup.next()
        HttpProxyHandlerFactory(eventLoop, addressResolverGroup.getResolver(eventLoop), it)
    }
    private val currentMinimumDelayMillis = atomic(0L)
    private val bootstrapTemplate = Bootstrap().apply {
        group(eventLoopGroup)
        option(ChannelOption.ALLOCATOR, SharedAllocator)
        option(ChannelOption.CONNECT_TIMEOUT_MILLIS, httpProperties.connectTimeout.inWholeMilliseconds.toInt())
        option(ChannelOption.SO_KEEPALIVE, true)
        option(ChannelOption.TCP_NODELAY, true)
        option(ChannelOption.WRITE_BUFFER_WATER_MARK, CommonWriteBufferWaterMark)
        if (Epoll.isAvailable()) {
            // The maximum amount of time (in milliseconds) that transmitted data may remain unacknowledged, or
            // buffered data may remain untransmitted (due to zero window size) before TCP will forcibly close the
            // corresponding connection.
            option(EpollChannelOption.TCP_USER_TIMEOUT, configuration.tcpUserTimeout.inWholeMilliseconds.toInt())
        }
        remoteAddress(serverAddress)
        resolver(addressResolverGroup)
    }.also {
        logger.info("Configured bootstrap: {}", it)
    }
    private val dispatcher = bootstrapTemplate.config().group().asCoroutineDispatcher()

    internal val allChannelCountEstimate: Int
        get() = allChannels.size

    suspend fun close() {
        addressResolverGroup.close()
        allChannels.close().awaitKt()
    }

    suspend fun make(): Channel {
        return internalMake()
    }

    @Suppress("Detekt.CognitiveComplexMethod") // FIXME
    private tailrec suspend fun internalMake(
        retries: Int = configuration.maximumConnectRetryAttempts
    ): Channel {
        runCatching {
            withContext(dispatcher) {
                nextRetryDelayMillis().let {
                    if (it > 0L) {
                        logger.info("{} will try to make a channel after a {}ms delay", this@ChannelFactory, it)
                    }
                    delay(it)
                }
                val bootstrap = bootstrapTemplate.pushikoClone(
                    proxyHandlerFactory,
                    sslContext,
                    serverAddress,
                    frameLogger,
                    configuration
                )
                suspendCoroutine { continuation ->
                    bootstrap.channelFactory(internalChannelFactory(continuation))
                        .connect()
                        .addListener {
                            updateMinimumDelay(it.isSuccess)
                            if (!it.isSuccess) {
                                continuation.tryResumeWithException(it.cause())
                            }
                        }
                }
            }.also {
                if (allChannels.add(it)) {
                    it.closeFuture().addListener { _ ->
                        allChannels.remove(it)
                    }
                }
            }
        }.onFailure {
            if (!it.canRetryAfter || retries < 1) {
                logger.warn("${this@ChannelFactory} failed to create a channel, not retrying further", it)
                throw it
            }
        }.onSuccess {
            return it
        }
        return internalMake(retries - 1)
    }

    private fun updateMinimumDelay(isSuccess: Boolean) {
        currentMinimumDelayMillis.update {
            if (isSuccess) {
                0L
            } else {
                it.doubleClamped()
            }
        }
    }

    private fun nextRetryDelayMillis() = configuration.let {
        minOf(
            currentMinimumDelayMillis.value + ThreadLocalRandom.current().nextLong(
                it.connectionRetryFuzzInterval.inWholeMilliseconds),
            it.maximumConnectRetryDelay.inWholeMilliseconds
        )
    }

    private fun Long.doubleClamped() = min(
        max(2 * this, configuration.minimumConnectRetryDelay.inWholeMilliseconds),
        configuration.maximumConnectRetryDelay.inWholeMilliseconds
    )

    private fun internalChannelFactory(
        continuation: Continuation<Channel>
    ) = object : ReflectiveChannelFactory<Channel>(
        bootstrapTemplate.config().group().javaClass.canonicalName.socketChannelClass()
    ) {
        override fun newChannel() = super.newChannel().apply {
            attr(channelContinuationAttributeKey).set(continuation)
        }
    }

    private fun <T> Continuation<T>.tryResumeWithException(cause: Throwable) = runCatching {
        resumeWithException(cause)
    }.getOrElse {
        logger.debug("Error resuming continuation", it)
    }
}

private val Throwable.canRetryAfter: Boolean
    get() = this is ConnectException || this is TimeoutException || this is ChannelInactiveException
