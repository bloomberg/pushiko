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

import com.bloomberg.pushiko.http.HttpRequest
import com.bloomberg.pushiko.http.HttpRequestContinuation
import com.bloomberg.pushiko.http.HttpResponse
import com.bloomberg.pushiko.http.exceptions.ChannelInactiveException
import com.bloomberg.pushiko.http.exceptions.ChannelStreamQuotaException
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.channel.EventLoop
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.Http2Connection
import io.netty.handler.codec.http2.Http2Connection.Endpoint
import io.netty.handler.codec.http2.Http2ConnectionDecoder
import io.netty.handler.codec.http2.Http2ConnectionEncoder
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2LocalFlowController
import io.netty.handler.codec.http2.Http2RemoteFlowController
import io.netty.handler.codec.http2.Http2Settings
import io.netty.handler.codec.http2.Http2Stream
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.Attribute
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.ScheduledFuture
import io.netty.util.concurrent.SucceededFuture
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.slf4j.Logger
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class ConnectionHandlerTest {
    private val pipeline = mock<ChannelPipeline>()
    private val isClosingAttribute = mock<Attribute<Boolean>>()
    private val eventLoop = mock<EventLoop>().apply {
        whenever(inEventLoop()) doReturn true
    }
    private val channel = mock<Channel>().apply {
        whenever(eventLoop()) doReturn eventLoop
        whenever(pipeline()) doReturn pipeline
        whenever(attr<Boolean>(argThat { name() == "channelIsClosing" })) doReturn isClosingAttribute
    }
    private val context = mock<ChannelHandlerContext>().apply {
        whenever(channel()) doReturn channel
        whenever(executor()) doReturn eventLoop
        whenever(newPromise()) doReturn mock()
    }
    private val local = mock<Endpoint<Http2LocalFlowController>>()
    private val connection = mock<Http2Connection>().apply {
        whenever(local()) doReturn local
    }
    private val flowController = mock<Http2RemoteFlowController>()

    @Suppress("TestFunctionName")
    private fun ConnectionHandler(
        monitorConnectionHealth: Boolean = false,
        settingsReadTimeoutMillis: Long = DEFAULT_SETTINGS_READ_TIMEOUT_MILLIS,
        encoder: Http2ConnectionEncoder = mock<Http2ConnectionEncoder>().apply {
            whenever(connection()) doReturn connection
            whenever(flowController()) doReturn flowController
        }
    ) = ConnectionHandler(
        mock<Http2ConnectionDecoder>().apply {
            whenever(connection()) doReturn connection
        }, encoder, mock(),
        monitorConnectionHealth = monitorConnectionHealth,
        settingsReadTimeoutMillis = settingsReadTimeoutMillis
    )

    @Test
    fun onGoAwayReadClosesChannel() {
        ConnectionHandler().onGoAwayRead(context, 1, 1, mock())
        verify(channel, times(1)).close()
    }

    @Test
    fun goAwayDebugDataIsEncodedWithoutChangingReaderIndex() {
        val data = Unpooled.wrappedBuffer(byteArrayOf(
            0,
            '\r'.code.toByte(),
            '\n'.code.toByte(),
            0,
            0xE2.toByte(), 0x80.toByte(), 0xA8.toByte(),
            0xE2.toByte(), 0x80.toByte(), 0xA9.toByte(),
            0x1B,
            '['.code.toByte(), '3'.code.toByte(), '1'.code.toByte(), 'm'.code.toByte(),
            0xFF.toByte()
        )).apply {
            skipBytes(1)
        }
        try {
            assertEquals("0d0a00e280a8e280a91b5b33316dff", data.goAwayDebugDataHex())
            assertEquals(1, data.readerIndex())
        } finally {
            data.release()
        }
    }

    @Test
    fun receivedGoAwayDebugDataLogIsBounded() {
        val logger = mock<Logger>()
        val data = Unpooled.wrappedBuffer(ByteArray(65) { it.toByte() })
        try {
            val encoded = data.goAwayDebugDataHex()
            logger.logGoAwayReceived(7, 11L, data)
            assertEquals(128, encoded.length)
            assertTrue(encoded.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue(encoded.startsWith("00010203"))
            assertTrue(encoded.endsWith("3c3d3e3f"))
            verify(logger, times(1)).info(
                eq("Received GOAWAY lastStreamId: {} errorCode: {} dataLength: {} dataHex: {} truncated: {}"),
                eq(7),
                eq(11L),
                eq(65),
                eq(encoded),
                eq(true)
            )
            verifyNoMoreInteractions(logger)
        } finally {
            data.release()
        }
    }

    @Test
    fun sentGoAwayDebugDataLogIncludesUntruncatedLength() {
        val logger = mock<Logger>()
        val data = Unpooled.wrappedBuffer(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), 0))
        try {
            logger.logGoAwaySent(3, 0L, data)
            verify(logger, times(1)).debug(
                eq("Sent GOAWAY lastStreamId: {} errorCode: {} dataLength: {} dataHex: {} truncated: {}"),
                eq(3),
                eq(0L),
                eq(3),
                eq("0d0a00"),
                eq(false)
            )
            verifyNoMoreInteractions(logger)
        } finally {
            data.release()
        }
    }

    @Test
    fun firstSettingsFrameCapturesStreamCapacity() {
        val readyContinuation = mock<Continuation<Channel>>()
        val continuationAttribute = mock<Attribute<Continuation<Channel>>>().apply {
            whenever(getAndSet(anyOrNull())) doReturn readyContinuation
        }
        val maxConcurrentStreamsAttribute = mock<Attribute<Long>>()
        whenever(channel.attr(channelContinuationAttributeKey)) doReturn continuationAttribute
        whenever(channel.attr(maxConcurrentStreamsAttributeKey)) doReturn maxConcurrentStreamsAttribute
        ConnectionHandler().onSettingsRead(context, Http2Settings().maxConcurrentStreams(150L))
        verify(maxConcurrentStreamsAttribute, times(1)).set(150L)
    }

    @Test
    fun subsequentSettingsFrameUpdatesStreamCapacity() {
        val readyContinuation = mock<Continuation<Channel>>()
        val continuationAttribute = mock<Attribute<Continuation<Channel>>>().apply {
            whenever(getAndSet(anyOrNull())).doReturn(readyContinuation, null)
        }
        val maxConcurrentStreamsAttribute = mock<Attribute<Long>>()
        whenever(channel.attr(channelContinuationAttributeKey)) doReturn continuationAttribute
        whenever(channel.attr(maxConcurrentStreamsAttributeKey)) doReturn maxConcurrentStreamsAttribute
        ConnectionHandler().apply {
            onSettingsRead(context, Http2Settings().maxConcurrentStreams(150L))
            onSettingsRead(context, Http2Settings().maxConcurrentStreams(30L))
        }
        verify(maxConcurrentStreamsAttribute, times(1)).set(150L)
        verify(maxConcurrentStreamsAttribute, times(1)).set(30L)
    }

    @Test
    fun initialSettingsTimeoutFailsCreationAndClosesChannel() {
        var failure: Throwable? = null
        val readyContinuation = object : Continuation<Channel> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Channel>) {
                failure = result.exceptionOrNull()
            }
        }
        val continuationAttribute = mock<Attribute<Continuation<Channel>>>().apply {
            whenever(getAndSet(anyOrNull())) doReturn readyContinuation
        }
        whenever(channel.attr(channelContinuationAttributeKey)) doReturn continuationAttribute
        val scheduledFuture = mock<ScheduledFuture<Void>>()
        lateinit var timeoutTask: Runnable
        whenever(eventLoop.schedule(any<Runnable>(), eq(25L), eq(TimeUnit.MILLISECONDS))) doAnswer {
            timeoutTask = it.arguments.first() as Runnable
            scheduledFuture
        }

        ConnectionHandler(settingsReadTimeoutMillis = 25L).userEventTriggered(
            context, SslHandshakeCompletionEvent.SUCCESS)
        timeoutTask.run()

        assertIs<SocketTimeoutException>(failure)
        verify(channel, times(1)).close()
    }

    @Test
    fun initialSettingsCancelsSettingsTimeout() {
        val readyContinuation = mock<Continuation<Channel>>()
        val continuationAttribute = mock<Attribute<Continuation<Channel>>>().apply {
            whenever(getAndSet(anyOrNull())) doReturn readyContinuation
        }
        val maxConcurrentStreamsAttribute = mock<Attribute<Long>>()
        whenever(channel.attr(channelContinuationAttributeKey)) doReturn continuationAttribute
        whenever(channel.attr(maxConcurrentStreamsAttributeKey)) doReturn maxConcurrentStreamsAttribute
        val scheduledFuture = mock<ScheduledFuture<Void>>()
        lateinit var timeoutTask: Runnable
        whenever(eventLoop.schedule(any<Runnable>(), eq(25L), eq(TimeUnit.MILLISECONDS))) doAnswer {
            timeoutTask = it.arguments.first() as Runnable
            scheduledFuture
        }
        val handler = ConnectionHandler(settingsReadTimeoutMillis = 25L)
        handler.userEventTriggered(context, SslHandshakeCompletionEvent.SUCCESS)
        handler.onSettingsRead(context, Http2Settings().maxConcurrentStreams(150L))
        timeoutTask.run()
        verify(scheduledFuture, times(1)).cancel(false)
        verify(channel, never()).close()
    }

    @Test
    fun failedTlsHandshakeDoesNotScheduleSettingsTimeout() {
        ConnectionHandler(settingsReadTimeoutMillis = 25L).userEventTriggered(
            context, SslHandshakeCompletionEvent(IllegalStateException("TLS failed")))
        verify(eventLoop, never()).schedule(any<Runnable>(), any<Long>(), any<TimeUnit>())
    }

    @Test
    fun requestHeaderLogOmitsCredentialsAndPath() {
        val logger = mock<Logger>()
        val headers = DefaultHttp2Headers()
            .method("POST")
            .path("/3/device/sensitive-device-token")
            .add("authorization", "Bearer sensitive-oauth-token")
        logger.traceRequestHeaders(3, headers)
        verify(logger, times(1)).trace(
            eq("Wrote request headers on stream {}: method={}"),
            eq(3),
            eq(headers.method())
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun responseHeaderLogOmitsHeaderValues() {
        val logger = mock<Logger>()
        val headers = DefaultHttp2Headers()
            .status("200")
            .add("set-cookie", "sensitive-session-cookie")
        logger.traceResponseHeaders(channel, 3, headers)
        verify(logger, times(1)).trace(
            eq("Read response headers: channel={} stream={} status={}"),
            eq(channel),
            eq(3),
            eq(headers.status())
        )
        verifyNoMoreInteractions(logger)
    }

    @Test
    fun responseBodyBufferCopiesTinyFramesWithoutComponents() {
        val body = newResponseBodyBuffer()
        val oneByteFrame = Unpooled.wrappedBuffer(byteArrayOf(7))
        try {
            repeat(1_024) {
                assertTrue(body.tryAppendResponseData(oneByteFrame))
            }
            assertFalse(body is CompositeByteBuf)
            assertEquals(1_024, body.readableBytes())
            assertEquals(0, oneByteFrame.readerIndex())
            assertEquals(7, body.getByte(1_023).toInt())
        } finally {
            oneByteFrame.release()
            body.release()
        }
    }

    @Test
    fun responseBodyBufferRejectsOversizedFrameBeforeCopying() {
        val body = newResponseBodyBuffer()
        val maximumBody = Unpooled.wrappedBuffer(ByteArray(body.maxCapacity()))
        val extraFrame = Unpooled.wrappedBuffer(byteArrayOf(1))
        try {
            assertTrue(body.tryAppendResponseData(maximumBody))
            assertFalse(body.tryAppendResponseData(extraFrame))
            assertEquals(body.maxCapacity(), body.readableBytes())
            assertEquals(0, extraFrame.readerIndex())
        } finally {
            extraFrame.release()
            maximumBody.release()
            body.release()
        }
    }

    @Test
    fun writeRejectsUnrecognisedMessage() {
        val promise = mock<ChannelPromise>()
        ConnectionHandler().write(context, Any(), promise)
        verify(promise, times(1)).tryFailure(any())
    }

    @Test
    fun writeWhenInactiveThrows() {
        val promise = mock<ChannelPromise>()
        whenever(channel.isActive) doReturn false
        ConnectionHandler().write(context, mock<HttpRequestContinuation>(), promise)
        verify(promise, times(1)).tryFailure(any<ChannelInactiveException>())
    }

    @Test
    fun exhaustedStreams() {
        val promise = mock<ChannelPromise>()
        whenever(channel.isActive) doReturn true
        val local = mock<Endpoint<Http2LocalFlowController>>().apply {
            whenever(incrementAndGetNextStreamId()) doReturn Int.MIN_VALUE
        }
        whenever(connection.local()) doReturn local
        ConnectionHandler().write(context, mock<HttpRequestContinuation>(), promise)
        verify(promise, times(1)).tryFailure(any<ChannelStreamQuotaException>())
    }

    @Test
    fun channelNotWriteable() {
        whenever(channel.isWritable) doReturn false
        ConnectionHandler().channelWritabilityChanged(context)
        verify(channel, times(1)).close()
    }

    @Test
    fun idleCloses() {
        val future = mock<ScheduledFuture<Void>>()
        whenever(eventLoop.schedule(any(), any(), any())) doAnswer {
            (it.arguments.first() as Runnable).run()
            future
        }
        ConnectionHandler().userEventTriggered(context, IdleStateEvent.READER_IDLE_STATE_EVENT)
        verify(channel, times(1)).close()
        verify(isClosingAttribute, times(1)).getAndSet(eq(true))
    }

    @Test
    fun pingCloses() {
        whenever(channel.isActive) doReturn true
        val future = mock<ScheduledFuture<Void>>()
        whenever(eventLoop.schedule(any(), any(), any())) doAnswer {
            (it.arguments.first() as Runnable).run()
            future
        }
        ConnectionHandler(monitorConnectionHealth = true).userEventTriggered(
            context, IdleStateEvent.READER_IDLE_STATE_EVENT)
        verify(channel, times(1)).close()
    }

    @Test
    fun responseTimeoutClosesStream() {
        val promise = mock<ChannelPromise>().apply {
            whenever(addListener(any<GenericFutureListener<Future<Void>>>())) doAnswer {
                @Suppress("UNCHECKED_CAST")
                (it.arguments.first() as GenericFutureListener<Future<Void>>).operationComplete(
                    SucceededFuture(eventLoop, null))
                this@apply
            }
        }
        val stream = mock<Http2Stream>().apply {
            whenever(id()) doReturn 3
        }
        whenever(local.incrementAndGetNextStreamId()) doReturn 3
        whenever(connection.stream(eq(3))) doReturn stream
        whenever(channel.isActive) doReturn true
        val future = mock<ScheduledFuture<Void>>()
        whenever(eventLoop.schedule(any(), any(), any())) doAnswer {
            (it.arguments.first() as Runnable).run()
            future
        }
        val continuation = HttpRequestContinuation(HttpRequest { }, channel, mock())
        ConnectionHandler(monitorConnectionHealth = true).write(context, continuation, promise)
        verify(stream, times(1)).close()
        verify(channel, never()).close()
    }

    @Test
    fun responseTimeoutCoversPendingWrite() {
        lateinit var timeoutTask: Runnable
        val timeoutFuture = mock<ScheduledFuture<Void>>()
        whenever(eventLoop.schedule(any<Runnable>(), eq(11L), eq(TimeUnit.SECONDS))) doAnswer {
            timeoutTask = it.arguments.first() as Runnable
            timeoutFuture
        }
        whenever(local.incrementAndGetNextStreamId()) doReturn 3
        whenever(channel.isActive) doReturn true
        val encoder = mock<Http2ConnectionEncoder>().apply {
            whenever(connection()) doReturn connection
            whenever(flowController()) doReturn flowController
        }
        var failure: Throwable? = null
        val continuation = HttpRequestContinuation(HttpRequest { }, channel, object : Continuation<HttpResponse> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<HttpResponse>) {
                failure = result.exceptionOrNull()
            }
        })
        ConnectionHandler(monitorConnectionHealth = true, encoder = encoder).write(context, continuation, mock())
        timeoutTask.run()
        assertIs<SocketTimeoutException>(failure)
        verify(encoder, times(1)).writeRstStream(
            eq(context), eq(3), eq(Http2Error.CANCEL.code()), any()
        )
    }

    @Test
    fun writeFailureCancelsResponseTimeout() {
        val timeoutFuture = mock<ScheduledFuture<Void>>()
        whenever(eventLoop.schedule(any<Runnable>(), eq(11L), eq(TimeUnit.SECONDS))) doReturn timeoutFuture
        whenever(local.incrementAndGetNextStreamId()) doReturn 3
        whenever(channel.isActive) doReturn true
        val failedWrite = mock<Future<Void>>().apply {
            whenever(isSuccess) doReturn false
            whenever(cause()) doReturn IllegalStateException("write failed")
        }
        val writePromise = mock<ChannelPromise>().apply {
            whenever(addListener(any<GenericFutureListener<Future<Void>>>())) doAnswer {
                @Suppress("UNCHECKED_CAST")
                (it.arguments.first() as GenericFutureListener<Future<Void>>).operationComplete(failedWrite)
                this@apply
            }
        }
        val continuation = HttpRequestContinuation(HttpRequest { }, channel, mock())
        ConnectionHandler().write(context, continuation, writePromise)
        verify(timeoutFuture, times(1)).cancel(false)
    }

    @Test
    fun closeSignals() {
        ConnectionHandler().close(context, mock())
        verify(isClosingAttribute, times(1)).getAndSet(eq(true))
    }

    /* FIXME Restore?
    @Test
    fun onStreamClosed() {
        val continuation = mock<Continuation<*>>()
        val stream = mock<Http2Stream>().apply {
            whenever(id()) doReturn 43
            whenever(getProperty<Continuation<*>>(anyOrNull())) doAnswer object : Answer<Continuation<*>> {
                private var count = 0

                override fun answer(invocation: InvocationOnMock) = when (count++) {
                    0 -> continuation
                    else -> null
                }
            }
        }
        val connection = mock<Http2Connection>()
        ConnectionHandler(
            mock<Http2ConnectionDecoder>().apply {
                whenever(connection()) doReturn connection
            },
            mock<Http2ConnectionEncoder>().apply {
                whenever(connection()) doReturn connection
            },
            mock()
        ).onStreamClosed(stream)
        verify(continuation, times(1)).resumeWithException(argThat {
            this is IOException && assertNotNull(message).startsWith("Stream 43 of connection")
        })
    }
     */
}
