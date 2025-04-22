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

import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder
import io.netty.handler.codec.http2.Http2ConnectionDecoder
import io.netty.handler.codec.http2.Http2ConnectionEncoder
import io.netty.handler.codec.http2.Http2Settings

internal class ConnectionHandlerBuilder : AbstractHttp2ConnectionHandlerBuilder<
    ConnectionHandler,
    ConnectionHandlerBuilder
>() {
    override fun build(
        decoder: Http2ConnectionDecoder,
        encoder: Http2ConnectionEncoder,
        initialSettings: Http2Settings
    ) = ConnectionHandler(
        decoder,
        encoder,
        initialSettings
    ).apply {
        frameListener(this)
    }

    fun maxConcurrentStreams(value: Long) = apply {
        initialSettings(initialSettings().maxConcurrentStreams(value))
    }

    override fun isServer() = true

    public override fun build(): ConnectionHandler = super.build()
}
