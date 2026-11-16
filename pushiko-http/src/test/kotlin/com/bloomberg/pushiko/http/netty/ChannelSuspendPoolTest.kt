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

import com.bloomberg.pushiko.health.Health
import com.bloomberg.pushiko.health.Status
import com.bloomberg.pushiko.http.IHttpClientProperties
import com.bloomberg.pushiko.pools.PoolConfiguration
import com.bloomberg.pushiko.pools.exceptions.PoolClosedException
import io.netty.channel.Channel
import io.netty.util.Attribute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@ExperimentalCoroutinesApi
internal class ChannelSuspendPoolTest {
    private fun poolConfiguration() = PoolConfiguration(
        errorRateThreshold = 0.5,
        fullScanPoolSize = 10,
        maximumPendingAcquisitions = 10,
        maximumSampledScan = 20,
        maximumSize = 1,
        minimumSize = 0,
        name = "pool",
        reaperDelay = Duration.INFINITE,
        summaryInterval = Duration.INFINITE
    )

    private fun alwaysAcquirableChannel(): Channel {
        val closingAttribute = mock<Attribute<Boolean>> {
            on { get() } doReturn false
        }
        val drainingAttribute = mock<Attribute<Boolean>> {
            on { get() } doReturn false
        }
        val maxConcurrentStreamsAttribute = mock<Attribute<Long>> {
            on { get() } doReturn 10L
        }
        return mock<Channel> {
            on { isActive } doReturn true
            on { attr(maxConcurrentStreamsAttributeKey) } doReturn maxConcurrentStreamsAttribute
            on { attr(streamCapacityChangedAttributeKey) } doReturn mock()
            on { attr(channelIsDrainingAttributeKey) } doReturn drainingAttribute
            on { attr<Boolean>(argThat { name() == "channelIsClosing" }) } doReturn closingAttribute
        }
    }

    @Test
    fun emptyChannelPoolSize() {
        ChannelPool(mock(), poolConfiguration()).run {
            runTest {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    assertEquals(0, metricsComponent.gauges.read(Duration.INFINITE).activeChannelCount)
                }
            }
        }
    }

    @Test
    fun close() = runTest {
        val factory = mock<PoolableChannelFactory> {
            onBlocking { make() } doSuspendableAnswer {
                PoolableChannel(alwaysAcquirableChannel(), mock<IHttpClientProperties>())
            }
        }
        ChannelPool(factory, poolConfiguration()).run {
            runCatching {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withPermit(Duration.INFINITE, mock<(Channel)->Unit>())
                }
            }.onFailure {
                assertTrue(it !is PoolClosedException)
            }
            close()
            assertFailsWith<PoolClosedException> {
                withPermit(Duration.INFINITE, mock<(Channel)->Unit>())
            }
        }
    }

    @Test
    fun healthPropagatesCallerCancellationDuringAcquisition() = runTest {
        val creating = CompletableDeferred<Unit>()
        val factory = mock<PoolableChannelFactory> {
            onBlocking { make() } doSuspendableAnswer {
                creating.complete(Unit)
                awaitCancellation()
            }
        }
        val pool = ChannelPool(factory, poolConfiguration())
        val outcome = CompletableDeferred<Result<Health>>()
        try {
            val checking = launch(Dispatchers.Default) {
                outcome.complete(runCatching { pool.healthComponent.acquisition.health(Duration.INFINITE) })
            }
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { creating.await() }
            }
            checking.cancelAndJoin()
            val result = outcome.await()
            assertIs<CancellationException>(
                result.exceptionOrNull(),
                "Health returned ${result.getOrNull()?.status}: ${result.getOrNull()?.throwable}"
            )
        } finally {
            pool.close()
        }
    }

    @Test
    fun healthReturnsDownWhenItsAcquisitionTimesOut() = runTest {
        val factory = mock<PoolableChannelFactory> {
            onBlocking { make() } doSuspendableAnswer { awaitCancellation() }
        }
        val pool = ChannelPool(factory, poolConfiguration())
        try {
            val health = pool.healthComponent.acquisition.health(1.seconds)
            assertEquals(Status.DOWN, health.status)
            assertIs<TimeoutCancellationException>(health.throwable)
            currentCoroutineContext().ensureActive()
        } finally {
            pool.close()
        }
    }

    @Test
    fun healthReturnsDownWhenPoolIsClosed() = runTest {
        val pool = ChannelPool(mock(), poolConfiguration())
        pool.close()
        val health = pool.healthComponent.acquisition.health(Duration.INFINITE)
        assertEquals(Status.DOWN, health.status)
        assertIs<PoolClosedException>(health.throwable)
        currentCoroutineContext().ensureActive()
    }
}
