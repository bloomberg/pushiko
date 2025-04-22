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

import com.bloomberg.pushiko.commons.slf4j.Logger
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.random.Random.Default.nextLong
import kotlin.time.Duration

private const val MIN_TTL_FACTOR = 0.9

private fun Duration.nextIntervalMillis() = maxOf(1L, inWholeMilliseconds.let {
    nextLong((MIN_TTL_FACTOR * it).toLong(), it + 1)
})

private class CloseConnectionTask(private val context: ChannelHandlerContext) : Runnable {
    private val logger = Logger()

    override fun run() {
        logger.info("Closing channel {} due to max_age", context.channel())
        context.pipeline().close()
    }
}

internal class MaximumAgeConnectionHandler internal constructor(
    private val timeToLive: Duration
) : ChannelDuplexHandler() {
    private val logger = Logger()
    private var context: ChannelHandlerContext? = null
    private var future: Future<*>? = null

    override fun channelActive(context: ChannelHandlerContext) {
        start(context)
        super.channelActive(context)
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        stop()
        super.channelInactive(context)
    }

    override fun channelRegistered(context: ChannelHandlerContext) {
        if (context.channel().isActive) {
            start(context)
        }
        super.channelRegistered(context)
    }

    override fun handlerAdded(context: ChannelHandlerContext) {
        context.channel().apply {
            if (isActive && isRegistered) {
                start(context)
            }
        }
        super.handlerAdded(context)
    }

    override fun handlerRemoved(context: ChannelHandlerContext) {
        stop()
        super.handlerRemoved(context)
    }

    private fun start(context: ChannelHandlerContext) {
        if (future != null) {
            return
        }
        this.context = context
        val nextIntervalMillis = timeToLive.nextIntervalMillis()
        logger.info("Starting max age handler, ttl: {}ms channel: {}", nextIntervalMillis, context.channel())
        future = context.channel().eventLoop().schedule(CloseConnectionTask(context), nextIntervalMillis,
            TimeUnit.MILLISECONDS)
    }

    private fun stop() {
        future?.let {
            logger.debug("Stopping max age handler, channel: {}", context!!.channel())
            it.cancel(false)
            future = null
        }
    }
}
