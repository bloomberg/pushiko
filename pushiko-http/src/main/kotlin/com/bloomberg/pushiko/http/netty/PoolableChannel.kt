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

import com.bloomberg.pushiko.http.ConcurrentRequestWaterMark
import com.bloomberg.pushiko.http.IHttpClientProperties
import com.bloomberg.pushiko.pool.Poolable
import com.bloomberg.pushiko.pool.WaterMarkScaleFactor
import io.netty.channel.Channel
import io.netty.handler.codec.http2.StreamBufferingEncoder
import io.netty.util.AttributeKey
import java.time.Duration
import java.time.Instant
import javax.annotation.concurrent.NotThreadSafe

internal val initialMaxConcurrentStreamsAttributeKey = AttributeKey.valueOf<Long>(
    Channel::class.java,
    "channelInitialMaxConcurrentStreams"
)

internal val Channel.initialMaxConcurrentStreams: Long?
    get() = attr(initialMaxConcurrentStreamsAttributeKey).get()

@NotThreadSafe
internal class PoolableChannel internal constructor(
    private val channel: Channel,
    internal val waterMark: ConcurrentRequestWaterMark
) : Poolable<Channel>(channel) {
    private val createdAt: Instant = Instant.now()

    constructor(
        channel: Channel,
        properties: IHttpClientProperties,
        waterMarkScaleFactor: WaterMarkScaleFactor = WaterMarkScaleFactor(),
        maxOutstandingResponses: Long? = channel.initialMaxConcurrentStreams
    ) : this(
        channel,
        ConcurrentRequestWaterMark(
            low = maxOf(
                maxOutstandingResponses?.let { (waterMarkScaleFactor.low * it).toLong() } ?: 1L,
                (waterMarkScaleFactor.low * properties.defaultMaximumConcurrentStreams).toLong()
            ),
            high = maxOf(
                maxOutstandingResponses?.let { (waterMarkScaleFactor.high * it).toLong() } ?: 1L,
                properties.defaultMaximumConcurrentStreams
            )
        )
    )

    override val maximumPermits: Int = waterMark.high.toInt()

    override val isAlive: Boolean
        get() = channel.let { it.isActive && !it.isClosing() }

    override val isCanAcquire: Boolean
        get() = allocatedPermits < waterMark.high

    override val isShouldAcquire: Boolean
        get() = allocatedPermits < waterMark.low

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
            .appendLine("    Outstanding requests: $allocatedPermits")
            .appendLine("      Low watermark: ${waterMark.low}")
            .appendLine("      High watermark: ${waterMark.high}")
            .appendLine("    Remote address: ${channel.remoteAddress()}")
            .appendLine("    Encoder $encoder:")
            .appendLine("      Buffered streams: ${encoder?.numBufferedStreams()}")
    }
}
