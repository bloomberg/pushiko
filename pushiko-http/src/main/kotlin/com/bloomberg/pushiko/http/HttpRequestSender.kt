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

package com.bloomberg.pushiko.http

import com.bloomberg.pushiko.http.netty.ChannelPool
import com.bloomberg.pushiko.http.netty.ConnectionHandler
import io.netty.channel.Channel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal class HttpRequestSender(
    internal val pool: ChannelPool,
    private val properties: IHttpClientProperties
) {
    @JvmSynthetic
    suspend fun send(request: HttpRequest): HttpResponse =
        pool.withPermit(properties.connectionAcquisitionTimeout) { channel ->
        suspendCancellableCoroutine { continuation ->
            val requestContinuation = HttpRequestContinuation(request, channel, continuation)
            continuation.invokeOnCancellation {
                requestContinuation.cancel()
                channel.eventLoop().execute {
                    channel.pipeline().get(ConnectionHandler::class.java)?.cancel(requestContinuation)
                }
            }
            channel.send(requestContinuation)
        }
    }

    private fun Channel.send(continuation: HttpRequestContinuation) {
        writeAndFlush(continuation).addListener {
            if (!it.isSuccess) {
                if (!continuation.isCancelled && it.cause() !is CancellationException) {
                    close()
                }
                runCatching {
                    continuation.resumeWithException(it.cause())
                }
            }
        }
    }
}
