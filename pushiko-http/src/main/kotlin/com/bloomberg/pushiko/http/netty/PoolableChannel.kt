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

package com.bloomberg.pushiko.http.netty

import com.bloomberg.pushiko.http.IHttpClientProperties
import com.bloomberg.pushiko.pools.Poolable
import com.bloomberg.pushiko.pools.WaterMarkScaleFactor
import io.netty.channel.Channel
import io.netty.channel.ChannelException
import io.netty.handler.codec.http2.Http2Exception
import io.netty.handler.codec.http2.StreamBufferingEncoder
import io.netty.util.AttributeKey
import java.io.IOException
import java.time.Duration
import java.time.Instant
import javax.annotation.concurrent.NotThreadSafe
import kotlin.coroutines.cancellation.CancellationException

internal val maxConcurrentStreamsAttributeKey = AttributeKey.valueOf<Long>(
    Channel::class.java,
    "channelMaxConcurrentStreams"
)

internal val Channel.maxConcurrentStreams: Long?
    get() = attr(maxConcurrentStreamsAttributeKey).get()

private const val NO_NEGOTIATED_LIMIT = -1L
private const val UNOBSERVED = Long.MIN_VALUE

@NotThreadSafe
internal class PoolableChannel internal constructor(
    private val channel: Channel,
    private val properties: IHttpClientProperties,
    private val waterMarkScaleFactor: WaterMarkScaleFactor = WaterMarkScaleFactor()
) : Poolable<Channel>(channel) {
    private val createdAt: Instant = Instant.now()

    private var observedMaxConcurrentStreams: Long = UNOBSERVED
    private var cachedLowWaterMark: Long = 0L
    private var cachedHighWaterMark: Long = 0L

    internal val lowWaterMark: Long
        get() {
            refreshWaterMark()
            return cachedLowWaterMark
        }

    internal val highWaterMark: Long
        get() {
            refreshWaterMark()
            return cachedHighWaterMark
        }

    override val maximumPermits: Int
        get() {
            refreshWaterMark()
            return cachedHighWaterMark.toInt()
        }

    override val isAlive: Boolean
        get() = channel.let { it.isActive && !it.isClosing() }

    override val isCanAcquire: Boolean
        get() {
            refreshWaterMark()
            return allocatedPermits < cachedHighWaterMark
        }

    override val isShouldAcquire: Boolean
        get() {
            refreshWaterMark()
            return allocatedPermits < cachedLowWaterMark
        }

    private fun refreshWaterMark() {
        val observed = channel.maxConcurrentStreams ?: NO_NEGOTIATED_LIMIT
        if (observed == observedMaxConcurrentStreams) {
            return
        }
        observedMaxConcurrentStreams = observed
        val negotiated = observed.takeIf { it != NO_NEGOTIATED_LIMIT }
        cachedLowWaterMark = maxOf(
            negotiated?.let { (waterMarkScaleFactor.low * it).toLong() } ?: 1L,
            (waterMarkScaleFactor.low * properties.defaultMaximumConcurrentStreams).toLong()
        )
        cachedHighWaterMark = maxOf(
            negotiated?.let { (waterMarkScaleFactor.high * it).toLong() } ?: 1L,
            properties.defaultMaximumConcurrentStreams
        )
    }

    override fun isError(throwable: Throwable): Boolean = when (throwable) {
        is CancellationException -> false
        is IOException,
        is Http2Exception,
        is ChannelException -> true
        else -> false
    }

    fun close() {
        channel.close()
    }

    override suspend fun summarize(appendable: Appendable) {
        super.summarize(appendable)
        val connectionHandler: ConnectionHandler? = channel.pipeline().get(ConnectionHandler::class.java)
        val connection = connectionHandler?.connection()
        val encoder = connectionHandler?.encoder() as StreamBufferingEncoder?
        appendable.appendLine("  Channel $channel:")
            .appendLine("    Active streams: ${connection?.run { local().numActiveStreams() }}")
            .appendLine("    Age: ${Duration.between(createdAt, Instant.now())}")
            .appendLine("    Bytes before unwritable: ${channel.bytesBeforeUnwritable()}")
            .appendLine("    Bytes before writable: ${channel.bytesBeforeWritable()}")
            .appendLine("    Created at: $createdAt")
            .appendLine("    GOAWAY received: ${connection?.goAwayReceived()}")
            .appendLine("    GOAWAY sent: ${connection?.goAwaySent()}")
            .appendLine("    Is active: ${channel.isActive}")
            .appendLine("    Is open: ${channel.isOpen}")
            .appendLine("    Is writable: ${channel.isWritable}")
            .appendLine("    Maximum permits: $maximumPermits")
            .appendLine("      Low watermark: $lowWaterMark")
            .appendLine("      High watermark: $highWaterMark")
            .appendLine("    Outstanding requests: $allocatedPermits")
            .appendLine("    Remote address: ${channel.remoteAddress()}")
            .appendLine("    Encoder $encoder:")
            .appendLine("      Buffered streams: ${encoder?.numBufferedStreams()}")
    }
}
