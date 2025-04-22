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

import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder
import io.netty.handler.codec.http2.Http2CodecUtil.MAX_CONCURRENT_STREAMS
import io.netty.handler.codec.http2.Http2ConnectionDecoder
import io.netty.handler.codec.http2.Http2ConnectionEncoder
import io.netty.handler.codec.http2.Http2FrameLogger
import io.netty.handler.codec.http2.Http2Settings
import javax.annotation.concurrent.NotThreadSafe
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
@JvmSynthetic
internal fun ConnectionHandler(
    block: ConnectionHandlerConfiguration.() -> Unit
): ConnectionHandler {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return ConnectionHandlerConfiguration().also {
        block(it)
    }.let {
        ConnectionHandlerBuilder().apply {
            it.frameLogger?.run {
                setFrameLogger(this)
                monitorConnection = it.monitorConnection
            }
        }.build()
    }
}

@NotThreadSafe
internal class ConnectionHandlerConfiguration {
    var frameLogger: Http2FrameLogger? = null
    var monitorConnection = false
}

@NotThreadSafe
private class ConnectionHandlerBuilder : AbstractHttp2ConnectionHandlerBuilder
<ConnectionHandler, ConnectionHandlerBuilder>() {
    init {
        initialSettings(initialSettings().maxConcurrentStreams(MAX_CONCURRENT_STREAMS))
    }

    var monitorConnection = false

    fun setFrameLogger(frameLogger: Http2FrameLogger) {
        super.frameLogger(frameLogger)
    }

    override fun encoderEnforceMaxConcurrentStreams() = true

    override fun isServer() = false

    public override fun build(): ConnectionHandler = super.build()

    override fun build(
        decoder: Http2ConnectionDecoder,
        encoder: Http2ConnectionEncoder,
        initialSettings: Http2Settings
    ) = ConnectionHandler(
        decoder,
        encoder,
        initialSettings,
        monitorConnection
    ).apply {
        frameListener(this)
    }
}
