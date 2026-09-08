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

package com.bloomberg.pushiko.http

import com.bloomberg.pushiko.http.netty.ChannelPool
import com.bloomberg.pushiko.http.netty.ConnectionHandler
import com.bloomberg.pushiko.http.netty.PoolableChannel
import com.bloomberg.pushiko.http.netty.PoolableChannelFactory
import com.bloomberg.pushiko.pools.PoolConfiguration
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelPipeline
import io.netty.channel.DefaultEventLoop
import io.netty.util.Attribute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.seconds

internal class HttpRequestSenderTest {
    private fun poolConfiguration() = PoolConfiguration(
        errorRateThreshold = 0.5,
        fullScanPoolSize = 1,
        maximumPendingAcquisitions = 2,
        maximumSampledScan = 1,
        maximumSize = 1,
        minimumSize = 0,
        name = "request-sender-test",
        reaperDelay = INFINITE,
        summaryInterval = INFINITE
    )

    @Test
    fun cancellationReachesHandlerAndReleasesPoolPermit() = runBlocking {
        val eventLoop = DefaultEventLoop()
        val pipeline = mock<ChannelPipeline>()
        val maximumStreams = mock<Attribute<Long>>()
        val closing = mock<Attribute<Boolean>>().apply {
            whenever(get()) doReturn false
        }
        val written = CompletableDeferred<HttpRequestContinuation>()
        val cancelled = CompletableDeferred<HttpRequestContinuation>()
        val handler = mock<ConnectionHandler> {
            on { cancel(any()) } doAnswer {
                cancelled.complete(it.arguments[0] as HttpRequestContinuation)
                Unit
            }
        }
        val channel = mock<Channel>().apply {
            whenever(eventLoop()) doReturn eventLoop
            whenever(isActive) doReturn true
            whenever(pipeline()) doReturn pipeline
            whenever(attr<Long>(eq(com.bloomberg.pushiko.http.netty.maxConcurrentStreamsAttributeKey))) doReturn
                maximumStreams
            whenever(attr<Boolean>(argThat { name() == "channelIsClosing" })) doReturn closing
            whenever(writeAndFlush(any<HttpRequestContinuation>())) doAnswer {
                written.complete(it.arguments[0] as HttpRequestContinuation)
                mock<ChannelFuture>()
            }
        }
        whenever(pipeline.get(eq(ConnectionHandler::class.java))) doReturn handler
        val properties = mock<IHttpClientProperties>().apply {
            whenever(connectionAcquisitionTimeout) doReturn INFINITE
            whenever(defaultMaximumConcurrentStreams) doReturn 100L
        }
        val poolable = PoolableChannel(channel, properties)
        val factory = mock<PoolableChannelFactory> {
            onBlocking { make() } doSuspendableAnswer { poolable }
        }
        val pool = ChannelPool(factory, poolConfiguration())
        try {
            val request = async {
                HttpRequestSender(pool, properties).send(HttpRequest {})
            }
            withTimeout(5.seconds) { written.await() }
            request.cancelAndJoin()
            val continuation = withTimeout(5.seconds) { cancelled.await() }
            assertTrue(continuation.isCancelled)
            withTimeout(5.seconds) {
                while (poolable.allocatedPermits != 0) {
                    kotlinx.coroutines.yield()
                }
            }
            assertEquals(0, poolable.allocatedPermits)
        } finally {
            pool.close()
            eventLoop.shutdownGracefully().syncUninterruptibly()
        }
    }
}
