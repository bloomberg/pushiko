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
import com.bloomberg.pushiko.pools.CommonMuxPool
import com.bloomberg.pushiko.pools.Factory
import com.bloomberg.pushiko.pools.PoolConfiguration
import com.bloomberg.pushiko.pools.Recycler
import com.bloomberg.pushiko.pools.WaterMarkScaleFactor
import io.netty.channel.Channel
import io.netty.channel.ChannelException
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2Exception
import io.netty.util.Attribute
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

internal class PoolableChannelTest {
    private fun properties(default: Long) = mock<IHttpClientProperties>().apply {
        whenever(defaultMaximumConcurrentStreams) doReturn default
    }

    private fun maxConcurrentStreamsAttribute(value: Long?) = mock<Attribute<Long>>().apply {
        value?.let { whenever(get()) doReturn it }
    }

    private fun channelReporting(
        attribute: Attribute<Long>,
        capacityChangedAttribute: Attribute<() -> Unit> = mock(),
        drainingAttribute: Attribute<Boolean> = mock {
            on { get() } doReturn false
        }
    ) = mock<Channel>().apply {
        whenever(attr(maxConcurrentStreamsAttributeKey)) doReturn attribute
        whenever(attr(streamCapacityChangedAttributeKey)) doReturn capacityChangedAttribute
        whenever(attr(channelIsDrainingAttributeKey)) doReturn drainingAttribute
    }

    private fun poolableChannel() = PoolableChannel(
        channelReporting(maxConcurrentStreamsAttribute(null)),
        mock()
    )

    @Test
    fun installsStreamCapacityChangeNotifier() {
        val capacityChangedAttribute = mock<Attribute<() -> Unit>>()
        PoolableChannel(
            channelReporting(maxConcurrentStreamsAttribute(0L), capacityChangedAttribute),
            properties(default = 100L)
        )
        verify(capacityChangedAttribute, times(1)).set(any())
    }

    @Test
    fun removalClearsStreamCapacityChangeNotifier() = runTest {
        val capacityChangedAttribute = mock<Attribute<() -> Unit>>()
        val channel = channelReporting(maxConcurrentStreamsAttribute(1L), capacityChangedAttribute).apply {
            whenever(isActive) doReturn true
        }
        val poolable = PoolableChannel(channel, properties(default = 1L))
        val factory = object : Factory<PoolableChannel>, Recycler<Channel> {
            private var makeCount = 0

            override val allocations: Int = 1

            override suspend fun make(): PoolableChannel = if (++makeCount == 1) {
                poolable
            } else {
                awaitCancellation()
            }

            override fun recycle(obj: Channel) = Unit

            override suspend fun close() = Unit
        }
        val pool = CommonMuxPool(
            PoolConfiguration(
                errorRateThreshold = 0.5,
                fullScanPoolSize = 1,
                maximumPendingAcquisitions = 1,
                maximumSampledScan = 1,
                maximumSize = 1,
                minimumSize = 1,
                reaperDelay = 10L.minutes,
                summaryInterval = 10L.minutes
            ),
            factory,
            factory
        )
        try {
            pool.prepare()
            whenever(channel.isActive) doReturn false

            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                pool.testAcquisition(100L.milliseconds)
            }

            verify(capacityChangedAttribute, times(1)).set(null)
        } finally {
            pool.close()
        }
    }

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
    fun negotiatedLimitBelowDefaultIsHonored() {
        val poolable = PoolableChannel(
            channelReporting(maxConcurrentStreamsAttribute(10L)),
            properties(default = 100L),
            WaterMarkScaleFactor(low = 0.5, high = 1.0)
        )
        assertEquals(5L, poolable.lowWaterMark)
        assertEquals(10L, poolable.highWaterMark)
        assertEquals(10, poolable.maximumPermits)
    }

    @Test
    fun zeroNegotiatedLimitPreventsAcquisition() {
        val poolable = PoolableChannel(
            channelReporting(maxConcurrentStreamsAttribute(0L)),
            properties(default = 100L)
        )
        assertEquals(0L, poolable.lowWaterMark)
        assertEquals(0L, poolable.highWaterMark)
        assertEquals(0, poolable.maximumPermits)
        assertFalse(poolable.isCanAcquire)
        assertFalse(poolable.isShouldAcquire)
    }

    @Test
    fun goAwayMakesChannelIneligibleWithoutDiscardingAllocatedPermits() {
        val drainingAttribute = mock<Attribute<Boolean>>().apply {
            whenever(get()) doReturn false
        }
        val closingAttribute = mock<Attribute<Boolean>>().apply {
            whenever(get()) doReturn false
        }
        val channel = channelReporting(
            maxConcurrentStreamsAttribute(100L),
            drainingAttribute = drainingAttribute
        ).apply {
            whenever(isActive) doReturn true
            whenever(attr<Boolean>(argThat { name() == "channelIsClosing" })) doReturn closingAttribute
        }
        val poolable = PoolableChannel(channel, properties(default = 100L)).apply {
            acquirePermit()
        }

        whenever(drainingAttribute.get()) doReturn true

        assertTrue(poolable.isAlive)
        assertTrue(poolable.isDraining)
        assertFalse(poolable.isCanAcquire)
        assertFalse(poolable.isShouldAcquire)
        assertEquals(1, poolable.allocatedPermits)

        poolable.releasePermit()
        assertFalse(poolable.isAlive)
    }

    @Test
    fun zeroNegotiatedLimitRecoversWhenPeerRaisesLimit() {
        val attribute = maxConcurrentStreamsAttribute(0L)
        val poolable = PoolableChannel(
            channelReporting(attribute),
            properties(default = 100L)
        )
        assertFalse(poolable.isCanAcquire)
        whenever(attribute.get()) doReturn 10L
        assertEquals(10L, poolable.highWaterMark)
        assertTrue(poolable.isCanAcquire)
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

    @Test
    fun extremeNegotiatedLimitIsClampedToIntMaximumForPoolPermits() {
        val poolable = PoolableChannel(
            channelReporting(maxConcurrentStreamsAttribute(4_294_967_295L)),
            properties(default = 100L),
            WaterMarkScaleFactor(low = 0.5, high = 1.0)
        )
        assertEquals(4_294_967_295L, poolable.highWaterMark)
        assertEquals(Int.MAX_VALUE, poolable.maximumPermits)
        assertTrue(poolable.isCanAcquire)
    }

    @Test
    fun signedIntBoundaryNegotiatedLimitIsClampedToIntMaximumForPoolPermits() {
        val poolable = PoolableChannel(
            channelReporting(maxConcurrentStreamsAttribute(2_147_483_648L)),
            properties(default = 100L)
        )
        assertEquals(2_147_483_648L, poolable.highWaterMark)
        assertEquals(Int.MAX_VALUE, poolable.maximumPermits)
    }
}
