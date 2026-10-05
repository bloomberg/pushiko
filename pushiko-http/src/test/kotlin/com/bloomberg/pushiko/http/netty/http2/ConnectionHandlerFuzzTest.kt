/*
 * Copyright 2026 Bloomberg Finance L.P.
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

package com.bloomberg.pushiko.http.netty.http2

import com.bloomberg.pushiko.http.IHttpClientProperties
import com.bloomberg.pushiko.http.netty.PoolableChannel
import com.bloomberg.pushiko.http.netty.channelContinuationAttributeKey
import com.bloomberg.pushiko.http.netty.channelIsDrainingAttributeKey
import com.bloomberg.pushiko.http.netty.maxConcurrentStreamsAttributeKey
import com.bloomberg.pushiko.http.netty.newResponseBodyBuffer
import com.bloomberg.pushiko.http.netty.streamCapacityChangedAttributeKey
import com.bloomberg.pushiko.http.netty.tryAppendResponseData
import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.EventLoop
import io.netty.handler.codec.http2.Http2Connection
import io.netty.handler.codec.http2.Http2ConnectionDecoder
import io.netty.handler.codec.http2.Http2ConnectionEncoder
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2LocalFlowController
import io.netty.handler.codec.http2.Http2RemoteFlowController
import io.netty.handler.codec.http2.Http2Settings
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.Attribute
import io.netty.util.AttributeKey
import java.io.ByteArrayOutputStream
import java.util.stream.Stream
import kotlin.math.min
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellableContinuation
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private inline fun <reified T> statefulAttribute(initial: T? = null): Attribute<T> {
    var current: T? = initial
    return mock<Attribute<T>>().apply {
        whenever(get()) doAnswer { current }
        whenever(set(anyOrNull())) doAnswer { current = it.getArgument(0) }
        whenever(getAndSet(anyOrNull())) doAnswer {
            val previous = current
            current = it.getArgument(0)
            previous
        }
    }
}

@Suppress("MagicNumber")
internal class ConnectionHandlerFuzzTest {
    @MethodSource("inputs")
    @FuzzTest
    fun fuzzResponseAccumulator(input: ByteArray) {
        if (input.size > MAX_INPUT_SIZE) {
            return
        }

        val body = newResponseBodyBuffer()
        val expected = ByteArrayOutputStream()
        var position = 0
        try {
            while (position < input.size) {
                val frameLength = min(MIN_FRAME_SIZE + (input[position].toInt() and 0xFF) * 32,
                    input.size - position)
                body.appendAndAssert(input.copyOfRange(position, position + frameLength), expected)
                position += frameLength
            }

            if (input.firstOrNull()?.toInt()?.and(BOUNDARY_CASE_FLAG) == BOUNDARY_CASE_FLAG) {
                val remainingCapacity = body.maxCapacity() - body.readableBytes()
                if (remainingCapacity > 0) {
                    body.appendAndAssert(ByteArray(remainingCapacity), expected)
                }
                body.appendAndAssert(byteArrayOf(input.last()), expected)
            }

            assertTrue(body.readableBytes() <= body.maxCapacity())
            assertContentEquals(expected.toByteArray(), body.toByteArray())
        } finally {
            assertTrue(body.release())
        }
    }

    private fun ByteBuf.appendAndAssert(frameBytes: ByteArray, expected: ByteArrayOutputStream) {
        val frame = Unpooled.wrappedBuffer(frameBytes)
        try {
            val accepted = tryAppendResponseData(frame)
            assertEquals(frameBytes.size <= maxCapacity() - expected.size(), accepted)
            assertEquals(0, frame.readerIndex())
            assertEquals(1, frame.refCnt())
            if (accepted) {
                expected.write(frameBytes)
            }
        } finally {
            assertTrue(frame.release())
        }
    }

    private fun ByteBuf.toByteArray(): ByteArray = ByteArray(readableBytes()).also {
        getBytes(readerIndex(), it)
    }

    @FuzzTest
    fun fuzzGoAwayRead(data: FuzzedDataProvider) {
        GoAwayReadFixture().use { fixture ->
            val handler = fixture.newHandler()
            val callCount = 1 + data.consumeInt(0, MAX_GOAWAY_CALLS_PER_RUN - 1)
            repeat(callCount) {
                val lastStreamId = data.consumeInt()
                val errorCode = if (data.consumeBoolean()) {
                    KNOWN_ERROR_CODES[data.consumeInt(0, KNOWN_ERROR_CODES.size - 1)]
                } else {
                    data.consumeLong()
                }
                val debugData = Unpooled.wrappedBuffer(data.consumeBytes(MAX_DEBUG_DATA_SIZE))
                try {
                    handler.onGoAwayRead(fixture.context, lastStreamId, errorCode, debugData)
                    assertEquals(0, debugData.readerIndex())
                    assertEquals(1, debugData.refCnt())
                } finally {
                    assertTrue(debugData.release() || debugData.capacity() == 0)
                }
            }
            verify(fixture.channel, atLeastOnce()).close()
        }
    }

    @FuzzTest
    fun fuzzSettingsRead(data: FuzzedDataProvider) {
        GoAwayReadFixture().use { fixture ->
            val handler = fixture.newHandler()
            val callCount = 1 + data.consumeInt(0, MAX_SETTINGS_CALLS_PER_RUN - 1)
            repeat(callCount) {
                val maxConcurrentStreams = data.consumeLong(0L, MAXIMUM_UNSIGNED_INT)
                handler.onSettingsRead(fixture.context, Http2Settings().maxConcurrentStreams(maxConcurrentStreams))
                assertEquals(maxConcurrentStreams, fixture.maxConcurrentStreamsAttribute.get())
            }
        }
    }

    @FuzzTest
    fun fuzzAvailabilityChangeIsSignalledWhenChannelStopsBeingAlive(data: FuzzedDataProvider) {
        GoAwayReadFixture().use { fixture ->
            val handler = fixture.newHandler()
            val poolable = PoolableChannel(fixture.channel, fixture.track(mock<IHttpClientProperties>()))
            var signals = 0
            fixture.streamCapacityChangedAttribute.set { signals++ }
            var remaining = 1 + data.consumeInt(0, MAX_LIFECYCLE_EVENTS_PER_RUN - 1)
            while (remaining-- > 0 && fixture.isActive) {
                val wasAlive = poolable.isAlive
                val signalsBefore = signals
                val event = data.consumeInt(0, LIFECYCLE_EVENT_KINDS - 1)
                when (event) {
                    0 -> handler.onGoAwayRead(
                        fixture.context,
                        data.consumeInt(),
                        data.consumeErrorCode(),
                        Unpooled.EMPTY_BUFFER
                    )
                    1 -> handler.onSettingsRead(
                        fixture.context,
                        Http2Settings().maxConcurrentStreams(data.consumeLong(0L, MAXIMUM_UNSIGNED_INT))
                    )
                    2 -> handler.close(fixture.context, fixture.track(mock()))
                    3 -> handler.userEventTriggered(fixture.context, IdleStateEvent.READER_IDLE_STATE_EVENT)
                    else -> {
                        fixture.isActive = false
                        handler.channelInactive(fixture.context)
                    }
                }
                if (wasAlive && !poolable.isAlive) {
                    assertTrue(signals > signalsBefore, "Channel stopped being alive on event $event without signalling")
                }
            }
        }
    }

    private fun FuzzedDataProvider.consumeErrorCode(): Long = if (consumeBoolean()) {
        KNOWN_ERROR_CODES[consumeInt(0, KNOWN_ERROR_CODES.size - 1)]
    } else {
        consumeLong()
    }

    private class GoAwayReadFixture : AutoCloseable {
        private val mocks = ArrayList<Any>()

        @Volatile
        var isActive = true
        val isDrainingAttribute = track(statefulAttribute<Boolean>(initial = false))
        val isClosingAttribute = track(statefulAttribute<Boolean>(initial = false))
        val maxConcurrentStreamsAttribute = track(statefulAttribute<Long>())
        val streamCapacityChangedAttribute = track(statefulAttribute<() -> Unit>())
        val channelContinuationAttribute = track(mock<Attribute<CancellableContinuation<Channel>>>())
        val pipeline = track(mock<ChannelPipeline>())
        val eventLoop = track(mock<EventLoop>()).apply {
            whenever(inEventLoop()) doReturn true
        }
        val channel = track(mock<Channel>()).apply {
            whenever(isActive) doAnswer { this@GoAwayReadFixture.isActive }
            whenever(eventLoop()) doReturn eventLoop
            whenever(pipeline()) doReturn pipeline
            whenever(attr(closingAttributeKey)) doReturn isClosingAttribute
            whenever(attr(channelIsDrainingAttributeKey)) doReturn isDrainingAttribute
            whenever(attr(streamCapacityChangedAttributeKey)) doReturn streamCapacityChangedAttribute
            whenever(attr(maxConcurrentStreamsAttributeKey)) doReturn maxConcurrentStreamsAttribute
            whenever(attr(channelContinuationAttributeKey)) doReturn channelContinuationAttribute
        }
        val context = track(mock<ChannelHandlerContext>()).apply {
            whenever(channel()) doReturn channel
            whenever(executor()) doReturn eventLoop
            whenever(newPromise()) doReturn track(mock())
        }
        val local = track(mock<Http2Connection.Endpoint<Http2LocalFlowController>>())
        val connection = track(mock<Http2Connection>()).apply {
            whenever(local()) doReturn local
        }
        val flowController = track(mock<Http2RemoteFlowController>())

        fun newHandler() = ConnectionHandler(
            track(mock<Http2ConnectionDecoder>()).apply {
                whenever(connection()) doReturn connection
            },
            track(mock<Http2ConnectionEncoder>()).apply {
                whenever(connection()) doReturn connection
                whenever(flowController()) doReturn flowController
            },
            Http2Settings()
        )

        fun <T : Any> track(mock: T): T = mock.also { mocks += it }

        override fun close() {
            mocks.forEach(Mockito.framework()::clearInlineMock)
            mocks.clear()
        }
    }

    companion object {
        private const val MAX_INPUT_SIZE = 4_096
        private const val MIN_FRAME_SIZE = 64
        private const val BOUNDARY_CASE_FLAG = 0x80
        private const val MAX_GOAWAY_CALLS_PER_RUN = 8
        private const val MAX_SETTINGS_CALLS_PER_RUN = 8
        private const val MAX_LIFECYCLE_EVENTS_PER_RUN = 16
        private const val LIFECYCLE_EVENT_KINDS = 5
        private const val MAX_DEBUG_DATA_SIZE = 256
        private const val MAXIMUM_UNSIGNED_INT = 0xFFFFFFFFL
        private val KNOWN_ERROR_CODES = Http2Error.entries.map(Http2Error::code)

        private val closingAttributeKey = AttributeKey.valueOf<Boolean>("channelIsClosing")

        @JvmStatic
        fun inputs(): Stream<ByteArray> = Stream.of(
            byteArrayOf(),
            byteArrayOf(1, 2, 3),
            byteArrayOf(BOUNDARY_CASE_FLAG.toByte()),
            byteArrayOf(BOUNDARY_CASE_FLAG.toByte(), 1, 2, 3)
        )
    }
}
