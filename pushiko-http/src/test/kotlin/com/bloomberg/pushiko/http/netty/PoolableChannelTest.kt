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

import com.bloomberg.pushiko.http.ConcurrentRequestWaterMark
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import kotlin.test.assertEquals

internal class PoolableChannelTest {
    @Test
    fun lowWatermark() {
        PoolableChannel(mock(), ConcurrentRequestWaterMark(30L, 150L)).apply {
            assertEquals(30L, waterMark.low)
        }
    }

    @Test
    fun highWatermark() {
        PoolableChannel(mock(), ConcurrentRequestWaterMark(30L, 150L)).apply {
            assertEquals(150L, waterMark.high)
        }
    }

    @Test
    fun lowWatermarkNullSettings() {
        PoolableChannel(mock(), ConcurrentRequestWaterMark(30L, 150L)).apply {
            assertEquals(30L, waterMark.low)
        }
    }

    @Test
    fun highWatermarkNullSettings() {
        PoolableChannel(mock(), ConcurrentRequestWaterMark(30L, 150L)).apply {
            assertEquals(150L, waterMark.high)
        }
    }
}
