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

import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.pushiko.commons.slf4j.ifDebugEnabled
import com.bloomberg.pushiko.commons.slf4j.ifInfoEnabled
import com.bloomberg.pushiko.commons.strings.commonPluralSuffix
import com.bloomberg.pushiko.http.HttpRequestContinuation
import com.bloomberg.pushiko.http.HttpResponse
import com.bloomberg.pushiko.http.exceptions.ChannelInactiveException
import com.bloomberg.pushiko.http.exceptions.ChannelStreamQuotaException
import com.bloomberg.pushiko.http.exceptions.ChannelWriteFailedException
import io.netty.buffer.ByteBuf
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http2.Http2Connection
import io.netty.handler.codec.http2.Http2ConnectionDecoder
import io.netty.handler.codec.http2.Http2ConnectionEncoder
import io.netty.handler.codec.http2.Http2ConnectionHandler
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2Exception
import io.netty.handler.codec.http2.Http2Flags
import io.netty.handler.codec.http2.Http2FrameListener
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2Settings
import io.netty.handler.codec.http2.Http2Stream
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.handler.timeout.WriteTimeoutException
import io.netty.handler.timeout.WriteTimeoutHandler
import io.netty.util.AttributeKey
import io.netty.util.collection.IntObjectHashMap
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.PromiseCombiner
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

private const val MAX_IDLE_CLOSE_DELAY_MILLIS = 5_000L
private const val MIN_IDLE_CLOSE_DELAY_MILLIS = 1_000L

private const val PING_TIMEOUT_SECONDS = 1L
// Firebase Cloud Messaging empirically has an undocumented internal timeout of 5 seconds, eventually responding with
// 500 Internal Error.
private const val RESPONSE_TIMEOUT_SECONDS = 11L

private val channelInactiveWriteException = ChannelInactiveException("Channel inactive when writing")
private val streamsExhaustedException = ChannelStreamQuotaException("HTTP/2 streams exhausted; closing connection")
private val unrecognisedMessageException = IllegalArgumentException("Unrecognised message object in pipeline")

private fun Channel.removeChannelContinuation(): Continuation<Channel>? =
    attr(channelContinuationAttributeKey).getAndSet(null)

private fun Channel.initialMaxConcurrentStreams(maxConcurrentStreams: Long) =
    attr(initialMaxConcurrentStreamsAttributeKey).set(maxConcurrentStreams)

private val channelIsClosingAttributeKey = AttributeKey.valueOf<Boolean>("channelIsClosing")
@JvmSynthetic
internal fun Channel.isClosing() = attr(channelIsClosingAttributeKey).get() ?: false
private fun Channel.signalIsClosing() = attr(channelIsClosingAttributeKey).getAndSet(true) != true

internal class ConnectionHandler(
    decoder: Http2ConnectionDecoder,
    encoder: Http2ConnectionEncoder,
    settings: Http2Settings,
    private val monitorConnectionHealth: Boolean = false
) : Http2ConnectionHandler(
    decoder,
    encoder,
    settings
), Http2FrameListener, Http2Connection.Listener {
    private val requestContinuations = IntObjectHashMap<HttpRequestContinuation>()
    private val responseTimeouts = IntObjectHashMap<Future<*>>()

    private val responseHeadersPropertyKey: Http2Connection.PropertyKey
    private val responseBodyPropertyKey: Http2Connection.PropertyKey
    private val requestContinuationPropertyKey: Http2Connection.PropertyKey

    private val logger = Logger()

    private var pingFuture: Future<*>? = null
    private var pingWriteTimeNanos = System.nanoTime()
    // Firebase Cloud Messaging empirically closes connections whenever it receives 3 pings without a request.
    private var pingedSinceLastWrite = false

    private var connectionError: Throwable? = null

    init {
        connection().let {
            responseHeadersPropertyKey = it.newKey()
            responseBodyPropertyKey = it.newKey()
            requestContinuationPropertyKey = it.newKey()
            it.addListener(this)
        }
    }

    override fun write(
        context: ChannelHandlerContext,
        message: Any,
        writePromise: ChannelPromise
    ) {
        if (message !is HttpRequestContinuation) {
            logger.error("Unexpected object in pipeline: {}", message)
            writePromise.tryFailure(unrecognisedMessageException)
            return
        }
        writePromise.addListener {
            if (!it.isSuccess) {
                logger.trace("Failed to write push notification", it.cause())
                message.tryResumeWithException(ChannelWriteFailedException(it.cause()))
            }
        }
        write(context, message, writePromise)
    }

    private fun write(
        context: ChannelHandlerContext,
        requestContinuation: HttpRequestContinuation,
        writePromise: ChannelPromise
    ) {
        if (!context.channel().isActive) {
            writePromise.tryFailure(channelInactiveWriteException)
            return
        }
        val streamId = connection().local().incrementAndGetNextStreamId()
        if (streamId < 0) {
            logger.info("Connection has exhausted its stream identifiers")
            writePromise.tryFailure(streamsExhaustedException)
            context.channel().close()
            return
        }

        requestContinuations[streamId] = requestContinuation

        val headersPromise = context.newPromise()
        encoder().writeHeaders(context, streamId, requestContinuation.request.headers, 0, false, headersPromise)
        logger.trace("Wrote headers on stream {}: {}", streamId, requestContinuation.request.headers)

        val bodyPromise = context.newPromise()
        // encoder().writeData() will release the ByteBuf.
        encoder().writeData(
            context,
            streamId,
            Unpooled.wrappedBuffer(requestContinuation.request.body),
            0,
            true,
            bodyPromise
        )

        writePromise.addListener {
            if (it.isSuccess) {
                pingedSinceLastWrite = false
                context.scheduleResponseTimeout(streamId)
            }
        }

        PromiseCombiner(context.executor()).apply {
            addAll(headersPromise as ChannelFuture, bodyPromise)
        }.finish(writePromise)
    }

    override fun channelActive(context: ChannelHandlerContext) {
        context.channel().closeFuture().addListener {
            val activeStreamsCount = connection().numActiveStreams()
            logger.info("Channel {} closed with {} active stream{}", context.channel(),
                activeStreamsCount, activeStreamsCount.commonPluralSuffix())
        }
        super.channelActive(context)
    }

    override fun onDataRead(
        context: ChannelHandlerContext,
        streamId: Int,
        data: ByteBuf,
        padding: Int,
        endOfStream: Boolean
    ): Int {
        pingFuture?.cancel(false)
        if (endOfStream) {
            responseTimeouts.remove(streamId).cancel(false)
        }
        val bytesAvailable = data.readableBytes()
        val bytesRead = bytesAvailable + padding
        logger.debug("onDataRead: available: {} read: {} stream: {}", bytesAvailable, bytesRead, streamId)
        val stream = connection().stream(streamId)
        val body = stream.run {
            if (bytesAvailable < 1) {
                getProperty<CompositeByteBuf>(responseBodyPropertyKey)
            } else {
                responseBodyBuffer()?.apply {
                    // The data buffer will be released by the codec.
                    addComponent(data.retain())
                    writerIndex(writerIndex() + bytesAvailable)
                }
            }
        }
        if (endOfStream) {
            stream.let {
                handleEndOfStream(it, it.removeProperty(responseHeadersPropertyKey), body)
            }
        }
        return bytesRead
    }

    override fun onHeadersRead(
        context: ChannelHandlerContext,
        streamId: Int,
        headers: Http2Headers,
        padding: Int,
        endOfStream: Boolean
    ) {
        logger.trace("onHeadersRead: channel: {} stream: {} headers: {}", context.channel(), streamId, headers)
        connection().stream(streamId).apply {
            if (endOfStream) {
                handleEndOfStream(this, headers, null)
            } else {
                setProperty(responseHeadersPropertyKey, headers)
            }
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
    ) {
        logger.info("RST_STREAM read error: {} code: {} streamId: {} channel: {}",
            Http2Error.valueOf(errorCode), errorCode, streamId, context.channel())
    }

    override fun onSettingsAckRead(context: ChannelHandlerContext) = Unit

    override fun onSettingsRead(context: ChannelHandlerContext, settings: Http2Settings) {
        // Always try to resume the "channel ready" continuation as a success after we receive a SETTINGS frame.
        // If it's the first SETTINGS frame, we know all handshaking and connection setup is done and the channel
        // is ready to use. If it's a subsequent SETTINGS frame, this will have no effect.
        context.channel().apply {
            removeChannelContinuation()?.let {
                logger.info("Initial settings from peer: {}", settings)
                settings.maxConcurrentStreams()?.run { initialMaxConcurrentStreams(this) }
                it.resume(this)
            } ?: logger.info("Received settings from peer: {}", settings)
        }
    }

    override fun onPingRead(context: ChannelHandlerContext, data: Long) = Unit

    override fun onPingAckRead(context: ChannelHandlerContext, data: Long) {
        pingFuture?.let {
            pingFuture = null
            it.cancel(false)
            logger.info("Ping ACK received in: {}ms channel: {}", System.currentTimeMillis() - data,
                context.channel())
        } ?: logger.warn("Ping ACK received, no PING sent")
    }

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
        data: ByteBuf
    ) {
        context.channel().close()
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
        flags: Http2Flags,
        payload: ByteBuf
    ) = Unit

    override fun channelWritabilityChanged(context: ChannelHandlerContext) {
        context.channel().run {
            if (!isWritable) {
                // Write requests made to a channel that is not writable are queued on the I/O thread until the
                // channel becomes writable. The channel might become writable again; even so, it will be closed.
                logger.info("Closing channel {}: It is not writable, {} bytes before writable", this,
                    bytesBeforeWritable())
                close()
            }
        }
        super.channelWritabilityChanged(context)
    }

    override fun onGoAwaySent(
        lastStreamId: Int,
        errorCode: Long,
        data: ByteBuf
    ) {
        logger.ifDebugEnabled {
            debug("Sent GOAWAY lastStreamId: {} errorCode: {} data: {}", lastStreamId, errorCode,
                data.toString(Charsets.UTF_8))
        }
    }

    override fun onGoAwayReceived(
        lastStreamId: Int,
        errorCode: Long,
        data: ByteBuf
    ) {
        logger.ifInfoEnabled {
            info("Received GOAWAY lastStreamId: {} errorCode: {} data: {}", lastStreamId, errorCode,
                data.toString(Charsets.UTF_8))
        }
    }

    override fun onConnectionError(
        context: ChannelHandlerContext,
        outbound: Boolean,
        cause: Throwable,
        connectionException: Http2Exception?
    ) {
        connectionError = connectionException ?: cause
        logger.warn("Channel ${context.channel()} encountered connection error", cause)
        context.channel().removeChannelContinuation()?.tryResumeWithException(cause)
        super.onConnectionError(context, outbound, cause, connectionException)
    }

    override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
        logger.info("Channel {} caught exception: {}", context.channel(), cause.toString())
        if (cause is WriteTimeoutException) {
            context.channel().pipeline().remove(WriteTimeoutHandler::class.java)
        }
        context.channel().removeChannelContinuation()?.tryResumeWithException(cause)
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        pingFuture?.cancel(false)
        requestContinuations.apply {
            entries.forEach {
                it.value.tryResumeWithException(streamClosedBeforeReplyException(it.key, context.channel()))
            }
            clear()
        }
        ChannelInactiveException("Channel became inactive before SETTINGS frame was received").let {
            context.channel().removeChannelContinuation()?.run {
                logger.debug(it.message)
                tryResumeWithException(it)
            }
        }
        super.channelInactive(context)
    }

    override fun onStreamAdded(stream: Http2Stream) {
        logger.trace("onStreamAdded: connection {} stream {}", connection(), stream.id())
        stream.setProperty(requestContinuationPropertyKey, requestContinuations.remove(stream.id()))
    }

    override fun onStreamActive(stream: Http2Stream) {
        logger.trace("onStreamActive: connection {} stream {}", connection(), stream.id())
    }

    override fun onStreamHalfClosed(stream: Http2Stream) {
        logger.trace("onStreamHalfClosed: connection {} stream {}", connection(), stream.id())
    }

    override fun onStreamClosed(stream: Http2Stream) {
        logger.trace("onStreamClosed: connection {} stream {}", connection(), stream.id())
        responseTimeouts.remove(stream.id())?.cancel(false)
        val continuation = stream.removeRequestContinuation() ?: return
        val throwable = connectionError ?: streamClosedBeforeReplyException(stream.id())
        continuation.tryResumeWithException(throwable)
    }

    override fun onStreamRemoved(stream: Http2Stream) {
        stream.run {
            logger.trace("onStreamRemoved: connection {} stream {}", connection(), id())
            removeProperty<Any?>(responseHeadersPropertyKey)
            relinquishResponseBody()
            removeRequestContinuation()
        }
    }

    override fun onStreamError(
        context: ChannelHandlerContext,
        outbound: Boolean,
        cause: Throwable,
        exception: Http2Exception.StreamException
    ) {
        logger.trace("onStreamError: connection {}", connection(), exception)
        connection().stream(exception.streamId())?.removeRequestContinuation()?.tryResumeWithException(exception)
        super.onStreamError(context, outbound, cause, exception)
    }

    override fun userEventTriggered(context: ChannelHandlerContext, event: Any) {
        logger.debug("userEventTriggered: {} channel: {}", event, context.channel())
        if (event is IdleStateEvent) {
            if (monitorConnectionHealth) {
                context.sendPing()
            } else {
                context.closeForIdle()
            }
        }
        super.userEventTriggered(context, event)
    }

    override fun close(
        context: ChannelHandlerContext,
        promise: ChannelPromise
    ) {
        context.doSignalIsClosing()
        pingFuture?.cancel(false)
        super.close(context, promise)
    }

    private fun ChannelHandlerContext.doSignalIsClosing() {
        if (channel().signalIsClosing()) {
            val activeStreamsCount = connection().numActiveStreams()
            logger.info("Channel {} is closing, has {} active stream{}", channel(),
                activeStreamsCount, activeStreamsCount.commonPluralSuffix())
        }
    }

    private fun ChannelHandlerContext.scheduleResponseTimeout(
        streamId: Int,
        timeoutSeconds: Long = RESPONSE_TIMEOUT_SECONDS
    ) {
        runCatching {
            responseTimeouts[streamId] = channel().eventLoop().schedule({
                connection().stream(streamId).run {
                    requestContinuation()?.tryResumeWithException(SocketTimeoutException(
                        "Response timed out after ${timeoutSeconds}s channel: ${channel()}"))
                    close()
                }
                if (!(monitorConnectionHealth || pingedSinceLastWrite) &&
                    secondsSinceLastPingWrite() >= timeoutSeconds) {
                    sendPing()
                }
            }, timeoutSeconds, TimeUnit.SECONDS)
        }.onFailure {
            logger.warn("Failed to register response timeout", it)
        }.onSuccess {
            logger.debug("Successfully registered response timeout: {}s", timeoutSeconds)
        }
    }

    private fun ChannelHandlerContext.sendPing(timeoutSeconds: Long = PING_TIMEOUT_SECONDS) {
        if (pingFuture?.isDone == false || !channel().isActive || channel().isClosing()) {
            return
        }
        runCatching {
            pingFuture = channel().eventLoop().schedule({
                connectionError = connectionError ?: SocketTimeoutException("Ping timed out channel: ${channel()}")
                channel().close()
            }, timeoutSeconds, TimeUnit.SECONDS)
            encoder().writePing(this, false, System.currentTimeMillis(), newPromise().addListener {
                if (it.isSuccess) {
                    pingedSinceLastWrite = true
                    pingWriteTimeNanos = System.nanoTime()
                } else {
                    connectionError = connectionError ?: it.cause()
                    channel().close()
                }
            })
            flush()
        }.onFailure {
            connectionError = connectionError ?: it
            channel().close()
        }.onSuccess {
            logger.trace("Wrote PING frame channel: {}", channel())
        }
    }

    private fun ChannelHandlerContext.closeForIdle() {
        channel().pipeline().remove(IdleStateHandler::class.java)
        val delayMillis = Random.nextLong(MIN_IDLE_CLOSE_DELAY_MILLIS, MAX_IDLE_CLOSE_DELAY_MILLIS)
        channel().eventLoop().schedule({
            channel().close()
        }, delayMillis, TimeUnit.MILLISECONDS)
        doSignalIsClosing()
        logger.info("Channel {} is closing in {}ms due to idle", channel(), delayMillis)
    }

    private fun secondsSinceLastPingWrite() = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - pingWriteTimeNanos)

    private fun handleEndOfStream(
        stream: Http2Stream,
        headers: Http2Headers,
        data: ByteBuf?
    ) {
        val continuation = stream.removeRequestContinuation()
        runCatching {
            val status = HttpResponseStatus.parseLine(headers.status())
            if (HttpResponseStatus.OK !== status && data == null) {
                logger.warn("Received an end-of-stream HEADERS frame for an unsuccessful notification")
            }
            continuation.resume(HttpResponse(
                status.code(),
                headers,
                data
            ))
        }.onFailure {
            stream.relinquishResponseBody()
            continuation.tryResumeWithException(it)
        }
    }

    private fun Http2Stream.requestContinuation() = getProperty<HttpRequestContinuation>(
        requestContinuationPropertyKey)

    private inline fun <T> Http2Stream.getPropertyOrDefault(key: Http2Connection.PropertyKey, supplier: () -> T?): T? =
        getProperty<T>(key) ?: supplier()?.also { setProperty(key, it) }

    private fun Http2Stream.removeRequestContinuation() = removeProperty<HttpRequestContinuation>(
        requestContinuationPropertyKey)

    private fun Http2Stream.responseHeaders() = getProperty<Http2Headers>(responseHeadersPropertyKey)

    private fun Http2Stream.responseBodyBuffer() = getPropertyOrDefault(responseBodyPropertyKey) {
        if (
            (requestContinuation() ?: return null).request.wantsResponseBody ||
            HttpResponseStatus.OK !== responseHeaders()?.let { HttpResponseStatus.parseLine(it.status()) }
        ) {
            // Firebase Cloud Messaging empirically sometimes responds in one part and indicates the end of stream,
            // and sometimes responds with an empty second part and indicates the end of the stream.
            Unpooled.compositeBuffer().also { setProperty(responseBodyPropertyKey, it) }
        } else {
            null
        }
    }

    private fun Http2Stream.relinquishResponseBody() {
        removeProperty<CompositeByteBuf>(responseBodyPropertyKey)?.release()
    }

    private fun <T> Continuation<T>.tryResumeWithException(cause: Throwable) = runCatching {
        resumeWithException(cause)
    }.getOrElse {
        logger.debug("Error resuming continuation", it)
    }

    private fun streamClosedBeforeReplyException(streamId: Int) = IOException(
        "Stream $streamId of connection ${connection()} was closed before a reply was received")

    private fun streamClosedBeforeReplyException(streamId: Int, channel: Channel) = IOException(
        "Stream $streamId of channel $channel was closed before a reply was received")
}
