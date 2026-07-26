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
import com.bloomberg.pushiko.http.exceptions.ChannelInactiveException
import com.bloomberg.pushiko.http.exceptions.ChannelStreamQuotaException
import com.bloomberg.pushiko.http.exceptions.ChannelWriteFailedException
import com.bloomberg.pushiko.http.exceptions.HttpClientClosedException
import com.bloomberg.pushiko.pools.WaterMarkScaleFactor
import io.netty.channel.Channel
import io.netty.channel.ChannelException
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2Exception
import io.netty.util.Attribute
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PoolableChannelTest {
    private fun properties(default: Long) = mock<IHttpClientProperties>().apply {
        whenever(defaultMaximumConcurrentStreams) doReturn default
    }

    private fun maxConcurrentStreamsAttribute(value: Long?) = mock<Attribute<Long>>().apply {
        value?.let { whenever(get()) doReturn it }
    }

    private fun channelReporting(attribute: Attribute<Long>) = mock<Channel>().apply {
        whenever(attr(maxConcurrentStreamsAttributeKey)) doReturn attribute
    }

    private fun poolableChannel() = PoolableChannel(mock(), mock())

    @Test
    fun ioErrorIsAttributedToTheChannel() {
        poolableChannel().run {
            assertTrue(isError(IOException("boom")))
            assertTrue(isError(ChannelInactiveException("inactive")))
            assertTrue(isError(ChannelStreamQuotaException("exhausted")))
            assertTrue(isError(ChannelWriteFailedException(IOException("write"))))
            assertTrue(isError(SocketTimeoutException("ping timed out")))
        }
    }

    @Test
    fun connectionProtocolErrorIsAttributedToTheChannel() {
        poolableChannel().run {
            assertTrue(isError(Http2Exception(Http2Error.PROTOCOL_ERROR)))
            assertTrue(isError(ChannelException("transport")))
        }
    }

    @Test
    fun cancellationIsNotAttributedToTheChannel() {
        poolableChannel().run {
            assertFalse(isError(ChannelClosedException))
        }
    }

    @Test
    fun applicationErrorIsNotAttributedToTheChannel() {
        poolableChannel().run {
            assertFalse(isError(HttpClientClosedException))
            assertFalse(isError(IllegalArgumentException("bad argument")))
            assertFalse(isError(RuntimeException("business logic")))
        }
    }

    @Test
    fun derivesWatermarkFromNegotiatedMaxConcurrentStreams() {
        val poolable = PoolableChannel(
            channelReporting(maxConcurrentStreamsAttribute(150L)),
            properties(default = 1L),
            WaterMarkScaleFactor(low = 0.5, high = 1.0)
        )
        assertEquals(75L, poolable.lowWaterMark)
        assertEquals(150L, poolable.highWaterMark)
        assertEquals(150, poolable.maximumPermits)
    }

    @Test
    fun watermarkIsFlooredByDefaultWhenNoSettingsNegotiated() {
        val poolable = PoolableChannel(
            channelReporting(maxConcurrentStreamsAttribute(null)),
            properties(default = 100L),
            WaterMarkScaleFactor(low = 0.5, high = 1.0)
        )
        assertEquals(50L, poolable.lowWaterMark)
        assertEquals(100L, poolable.highWaterMark)
    }

    @Test
    fun watermarkShrinksWhenPeerLowersMaxConcurrentStreams() {
        val attribute = maxConcurrentStreamsAttribute(100L)
        val poolable = PoolableChannel(
            channelReporting(attribute),
            properties(default = 1L),
            WaterMarkScaleFactor(low = 0.5, high = 1.0)
        ).apply {
            repeat(40) { acquirePermit() }
        }
        assertEquals(100L, poolable.highWaterMark)
        assertTrue(poolable.isCanAcquire)
        whenever(attribute.get()) doReturn 10L
        assertEquals(10L, poolable.highWaterMark)
        assertEquals(10, poolable.maximumPermits)
        assertFalse(poolable.isCanAcquire)
    }

    @Test
    fun watermarkGrowsWhenPeerRaisesMaxConcurrentStreams() {
        val attribute = maxConcurrentStreamsAttribute(50L)
        val poolable = PoolableChannel(
            channelReporting(attribute),
            properties(default = 1L),
            WaterMarkScaleFactor(low = 0.5, high = 1.0)
        ).apply {
            repeat(50) { acquirePermit() }
        }
        assertEquals(50L, poolable.highWaterMark)
        assertFalse(poolable.isCanAcquire)
        whenever(attribute.get()) doReturn 200L
        assertEquals(200L, poolable.highWaterMark)
        assertEquals(200, poolable.maximumPermits)
        assertTrue(poolable.isCanAcquire)
    }
}
