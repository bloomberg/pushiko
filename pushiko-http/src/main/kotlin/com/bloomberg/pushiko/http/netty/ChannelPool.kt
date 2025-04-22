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

import com.bloomberg.pushiko.health.Health
import com.bloomberg.pushiko.health.Status
import com.bloomberg.pushiko.http.IHttpClientProperties
import com.bloomberg.pushiko.pool.CommonMuxPool
import com.bloomberg.pushiko.pool.PoolConfiguration
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import io.netty.handler.codec.http2.Http2FrameLogger
import io.netty.handler.ssl.SslContext
import kotlinx.coroutines.ensureActive
import java.net.InetSocketAddress
import javax.annotation.concurrent.ThreadSafe
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

@Suppress("Detekt.LongParameterList")
@JvmSynthetic
internal fun ChannelPool(
    serverAddress: InetSocketAddress,
    sslContext: SslContext,
    eventLoopGroup: EventLoopGroup,
    httpProperties: IHttpClientProperties,
    frameLogger: Http2FrameLogger?
): ChannelPool {
    val factory = ChannelFactory(
        serverAddress,
        sslContext,
        eventLoopGroup,
        httpProperties,
        frameLogger,
        httpProperties.factoryConfiguration()
    )
    return ChannelPool(
        PoolableChannelFactory(factory, httpProperties),
        httpProperties.poolConfiguration()
    )
}

private fun IHttpClientProperties.poolConfiguration() = PoolConfiguration(
    acquisitionAttemptsThreshold = (maximumConnections + 1) / 2,
    maximumPendingAcquisitions = maximumPendingAcquisitions,
    maximumSize = maximumConnections,
    minimumSize = minimumConnections,
    reaperDelay = reaperDelay,
    summaryInterval = summaryInterval
)

@ThreadSafe
internal class ChannelPool(
    factory: PoolableChannelFactory,
    configuration: PoolConfiguration
) {
    private val pool = CommonMuxPool(configuration, factory, factory)

    /**
     * @since 0.24.0
     */
    @JvmField
    val healthComponent = HealthComponent()

    @JvmField
    val metricsComponent = MetricsComponent()

    /**
     * Request a single permit be acquired for a pooled channel, make use of it inside the received
     * block, and then release the permit.
     *
     * For an HTTP/2 channel for instance, one permit grants the block the exclusive use of a
     * single stream. This would be enough to send just a single request and receive its response.
     *
     * @param acquisitionTimeout after which a [kotlinx.coroutines.TimeoutCancellationException] is
     *   thrown if a permit was not acquired in time.
     * @param block to receive the pooled channel once a permit has been acquired.
     *
     * @return the result of executing [block].
     *
     * @throws [kotlinx.coroutines.TimeoutCancellationException] if a permit was not be acquired
     *   within the received timeout.
     */
    @JvmSynthetic
    suspend inline fun <R : Any> withPermit(
        acquisitionTimeout: Duration,
        block: (Channel) -> R
    ) = pool.withPermit(acquisitionTimeout, block)

    /**
     * @since 0.19.0
     */
    @JvmSynthetic
    suspend fun prepare() = pool.prepare()

    @JvmSynthetic
    suspend fun close() = pool.close()

    /**
     * Performs an acquisition of a channel and immediately returns the acquired channel back to this pool
     * without writing a request.
     */
    @JvmSynthetic
    internal suspend fun testChannelAcquisition(timeout: Duration) = pool.testAcquisition(timeout)

    @ThreadSafe
    internal inner class HealthComponent internal constructor() {
        val acquisition: AcquisitionHealthCheck = AcquisitionHealthCheck()
    }

    @ThreadSafe
    internal inner class AcquisitionHealthCheck {
        private val healthy = Health(Status.UP)

        @JvmSynthetic
        suspend fun health(timeout: Duration): Health {
            coroutineContext.ensureActive()
            return runCatching {
                testChannelAcquisition(timeout)
                healthy
            }.getOrElse {
                Health(Status.DOWN, it)
            }
        }
    }

    @ThreadSafe
    inner class MetricsComponent internal constructor() {
        val gauges: Gauges = Gauges()
    }

    @ThreadSafe
    inner class Gauges {
        @JvmSynthetic
        suspend fun read(timeout: Duration): Metrics {
            coroutineContext.ensureActive()
            return pool.metricsComponent.gauges(timeout).let {
                Metrics(
                    activeChannelCount = it.allocatedSize
                )
            }
        }
    }

    @ThreadSafe
    data class Metrics(
        val activeChannelCount: Int
    )
}
