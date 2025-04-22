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

package com.bloomberg.pushiko.server

import com.bloomberg.pushiko.commons.slf4j.Logger
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.Http2ConnectionDecoder
import io.netty.handler.codec.http2.Http2ConnectionEncoder
import io.netty.handler.codec.http2.Http2ConnectionHandler
import io.netty.handler.codec.http2.Http2Exception
import io.netty.handler.codec.http2.Http2Flags
import io.netty.handler.codec.http2.Http2FrameListener
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2Settings
import io.netty.handler.codec.http2.Http2Stream

internal class ConnectionHandler(
    decoder: Http2ConnectionDecoder,
    encoder: Http2ConnectionEncoder,
    settings: Http2Settings
) : Http2ConnectionHandler(
    decoder,
    encoder,
    settings
), Http2FrameListener {
    private val logger = Logger()
    private val headersPropertyKey = connection().newKey()

    override fun onDataRead(
        context: ChannelHandlerContext,
        streamId: Int,
        data: ByteBuf,
        padding: Int,
        endOfStream: Boolean
    ): Int {
        val bytesRead = data.readableBytes() + padding
        val stream = connection().stream(streamId)
        if (endOfStream) {
            handleEndOfStream(context, stream)
        }
        return bytesRead
    }

    override fun write(
        context: ChannelHandlerContext,
        message: Any,
        promise: ChannelPromise
    ) {
        val response = message as FakeResponse
        encoder().writeHeaders(
            context,
            message.stream.id(),
            DefaultHttp2Headers().status(
                when (response) {
                    is OkResponse -> HttpResponseStatus.OK
                    is NotFoundResponse -> HttpResponseStatus.NOT_FOUND
                    is NoResponse -> return
                }.codeAsText()
            ),
            0,
            true,
            promise
        )
    }

    override fun onConnectionError(
        context: ChannelHandlerContext,
        outbound: Boolean,
        cause: Throwable?,
        http2Exception: Http2Exception?
    ) {
        logger.error("Channel encountered connection error", cause)
        super.onConnectionError(context, outbound, cause, http2Exception)
    }

    override fun onHeadersRead(
        context: ChannelHandlerContext,
        streamId: Int,
        headers: Http2Headers,
        padding: Int,
        endOfStream: Boolean
    ) {
        val stream = connection().stream(streamId)
        stream.setProperty(headersPropertyKey, headers)
        if (endOfStream) {
            handleEndOfStream(context, stream)
        }
    }

    override fun onHeadersRead(
        context: ChannelHandlerContext,
        streamId: Int,
        headers: Http2Headers,
        streamDependency: Int,
        weight: Short,
        exclusive: Boolean,
        padding: Int,
        endOfStream: Boolean
    ) = onHeadersRead(context, streamId, headers, padding, endOfStream)

    override fun onPriorityRead(
        context: ChannelHandlerContext,
        streamId: Int,
        streamDependency: Int,
        weight: Short,
        exclusive: Boolean
    ) = Unit

    override fun onRstStreamRead(
        context: ChannelHandlerContext,
        streamId: Int,
        errorCode: Long
    ) = Unit

    override fun onSettingsAckRead(context: ChannelHandlerContext) = Unit

    override fun onSettingsRead(context: ChannelHandlerContext, settings: Http2Settings) = Unit

    override fun onPingRead(context: ChannelHandlerContext, data: Long) = Unit

    override fun onPingAckRead(context: ChannelHandlerContext, data: Long) = Unit

    override fun onPushPromiseRead(
        context: ChannelHandlerContext,
        streamId: Int,
        promisedStreamId: Int,
        headers: Http2Headers,
        padding: Int
    ) = Unit

    override fun onGoAwayRead(
        context: ChannelHandlerContext,
        lastStreamId: Int,
        errorCode: Long,
        debugData: ByteBuf
    ) {
        logger.info("Server read GOAWAY, channel: {}", context.channel())
        context.close()
    }

    override fun onWindowUpdateRead(
        context: ChannelHandlerContext,
        streamId: Int,
        windowSizeIncrement: Int
    ) = Unit

    override fun onUnknownFrame(
        context: ChannelHandlerContext,
        frameType: Byte,
        streamId: Int,
        flags: Http2Flags?,
        payload: ByteBuf?
    ) = throw IllegalStateException("Unknown frame")

    private fun handleEndOfStream(
        context: ChannelHandlerContext,
        stream: Http2Stream
    ) {
        val headers = stream.removeProperty<Http2Headers>(headersPropertyKey)
        try {
            write(
                context,
                when (headers.path().toString()) {
                    "/ok" -> OkResponse(stream)
                    "/crash" -> error("Server crash")
                    "/silence" -> NoResponse(stream)
                    "/sleep/1" -> {
                        Thread.sleep(1_000L)
                        OkResponse(stream)
                    }
                    "/sleep/5" -> {
                        Thread.sleep(5_000L)
                        OkResponse(stream)
                    }
                    "/sleep/10" -> {
                        Thread.sleep(10_000L)
                        OkResponse(stream)
                    }
                    else -> NotFoundResponse(stream)
                },
                context.newPromise()
            )
        } finally {
            context.flush()
        }
    }
}
