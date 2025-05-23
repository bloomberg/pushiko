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

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http2.Http2FrameLogger
import io.netty.handler.codec.http2.Http2Settings
import org.slf4j.Logger
import org.slf4j.event.Level

internal class PushikoHttp2FrameLogger(
    private val logger: Logger,
    level: Level
) : Http2FrameLogger(level.nettyLevel()) {
    override fun logSettings(
        direction: Direction,
        context: ChannelHandlerContext,
        settings: Http2Settings
    ) {
        logger.info("{} {} SETTINGS: ack=false settings={}", context.channel(), direction.name, settings)
    }

    override fun isEnabled() = false
}
