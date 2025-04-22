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
import com.bloomberg.pushiko.http.exceptions.HttpClientClosedException
import com.bloomberg.pushiko.http.netty.ChannelPool
import com.bloomberg.pushiko.pool.exceptions.PoolClosedException
import io.netty.channel.EventLoopGroup
import io.netty.handler.codec.http2.Http2Exception
import io.netty.handler.codec.http2.Http2FrameLogger
import io.netty.handler.ssl.SslContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.InetSocketAddress
import javax.annotation.concurrent.ThreadSafe
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

@ThreadSafe
class HttpClient internal constructor(
    private val sender: HttpRequestSender,
    private val properties: IHttpClientProperties
) {
    internal constructor(
        serverAddress: InetSocketAddress,
        sslContext: SslContext,
        eventLoopGroup: EventLoopGroup,
        properties: IHttpClientProperties = OptionalHttpProperties(),
        frameLogger: Http2FrameLogger? = null,
    ) : this(HttpRequestSender(ChannelPool(
        serverAddress,
        sslContext,
        eventLoopGroup,
        properties,
        frameLogger
    ), properties), properties)

    init {
        HttpClientRegistry.add(this)
    }

    private val logger = Logger()

    @JvmField
    val healthComponent = HealthComponent()

    @JvmField
    val metricsComponent = MetricsComponent()

    /**
     * @throws CancellationException if the job of the coroutine context is cancelled or completed.
     *
     * @since 0.19.0
     */
    @JvmSynthetic
    suspend fun prepare() {
        sender.pool.prepare()
    }

    /**
     * Send an HTTP request and suspend the current coroutine until either a response is received from the peer
     * or an exception occurs.
     *
     * @param request to send.
     *
     * @return HTTP response.
     *
     * @throws HttpClientClosedException if the client is closed.
     * @throws CancellationException if the job of the coroutine context is cancelled or completed.
     * @throws IOException
     *
     * @since 0.1.0
     */
    @JvmSynthetic
    suspend fun send(request: HttpRequest) = try {
        doSend(request)
    } catch (e: Http2Exception) {
        throw IOException(e)
    }

    @JvmSynthetic
    suspend fun close() {
        HttpClientRegistry.remove(this)
        sender.pool.close()
        logger.info("HTTP client {} is shutdown", this)
    }

    private suspend fun doSend(request: HttpRequest): HttpResponse {
        var retries = 0
        while (true) {
            coroutineContext.ensureActive()
            runCatching {
                return sender.send(request)
            }.onFailure { e ->
                if (e is PoolClosedException) {
                    throw HttpClientClosedException
                } else if (
                    e is CancellationException ||
                    properties.let { !(retries++ < it.maximumRequestRetries && it.retryPolicy.canRetryRequestAfter(e)) }
                ) {
                    throw e
                }
            }
        }
    }

    inner class HealthComponent internal constructor() {
        val connectivity = ConnectivityHealthCheck()
    }

    @ThreadSafe
    inner class ConnectivityHealthCheck internal constructor() {
        /**
         * Performs a connectivity health check on this HTTP client. This check does not send a request,
         * and has a prompt cancellation guarantee.
         *
         * The status of the health check may be [com.bloomberg.pushiko.health.Status.DOWN], temporarily or
         * indefinitely, if:
         *
         * * The channel pool is closed, or
         * * There are no channels in the pool, or
         * * All slots with the available channels are currently claimed and requests are buffering.
         *
         * @since 0.24.0
         */
        @JvmSynthetic
        suspend fun health(timeout: Duration) = sender.pool.healthComponent.acquisition.health(timeout)
    }

    @ThreadSafe
    inner class MetricsComponent internal constructor() {
        val gauges = Gauges()
    }

    @ThreadSafe
    inner class Gauges {
        @JvmSynthetic
        suspend fun read(timeout: Duration): Metrics = sender.pool.metricsComponent.gauges.read(timeout).let {
            Metrics(
                connectionCount = it.activeChannelCount
            )
        }
    }

    @ThreadSafe
    data class Metrics(
        val connectionCount: Int
    )
}
