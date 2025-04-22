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

import com.bloomberg.pushiko.pool.PoolConfiguration
import com.bloomberg.pushiko.pool.exceptions.PoolClosedException
import io.netty.channel.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

@ExperimentalCoroutinesApi
internal class ChannelSuspendPoolTest {
    @Test
    fun emptyChannelPoolSize() {
        val configuration = mock<PoolConfiguration> {
            whenever(it.name) doReturn "pool"
        }
        ChannelPool(mock(), configuration).run {
            runTest {
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    assertEquals(0, metricsComponent.gauges.read(Duration.INFINITE).activeChannelCount)
                }
            }
        }
    }

    @Test
    fun close() = runTest {
        val configuration = mock<PoolConfiguration> {
            whenever(it.maximumPendingAcquisitions) doReturn 10
            whenever(it.maximumSize) doReturn 1
            whenever(it.name) doReturn "pool"
        }
        val factory = mock<PoolableChannelFactory> {
            onBlocking { make() } doSuspendableAnswer {
                mock {
                    whenever(it.isAlive) doReturn true
                    whenever(it.isCanAcquire) doReturn true
                    whenever(it.isShouldAcquire) doReturn true
                    whenever(it.maximumPermits) doReturn 10
                }
            }
        }
        ChannelPool(factory, configuration).run {
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
}
