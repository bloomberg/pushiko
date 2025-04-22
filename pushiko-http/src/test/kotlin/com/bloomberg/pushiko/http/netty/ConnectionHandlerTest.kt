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
import com.bloomberg.pushiko.http.exceptions.ChannelInactiveException
import com.bloomberg.pushiko.http.exceptions.ChannelStreamQuotaException
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.channel.EventLoop
import io.netty.handler.codec.http2.Http2Connection
import io.netty.handler.codec.http2.Http2Connection.Endpoint
import io.netty.handler.codec.http2.Http2ConnectionDecoder
import io.netty.handler.codec.http2.Http2ConnectionEncoder
import io.netty.handler.codec.http2.Http2LocalFlowController
import io.netty.handler.codec.http2.Http2RemoteFlowController
import io.netty.handler.codec.http2.Http2Stream
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.Attribute
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.ScheduledFuture
import io.netty.util.concurrent.SucceededFuture
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test

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
        monitorConnectionHealth: Boolean = false
    ) = ConnectionHandler(
        mock<Http2ConnectionDecoder>().apply {
            whenever(connection()) doReturn connection
        }, mock<Http2ConnectionEncoder>().apply {
            whenever(connection()) doReturn connection
            whenever(flowController()) doReturn flowController
        }, mock(),
        monitorConnectionHealth = monitorConnectionHealth
    )

    @Test
    fun onGoAwayReadClosesChannel() {
        ConnectionHandler().onGoAwayRead(context, 1, 1, mock())
        verify(channel, times(1)).close()
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
