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

package com.bloomberg.pushiko.http.netty.http2

import io.netty.handler.codec.http2.Http2FrameLogger.Direction.INBOUND
import io.netty.handler.codec.http2.Http2FrameLogger.Direction.OUTBOUND
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.slf4j.Logger
import org.slf4j.event.Level
import kotlin.test.assertFalse

internal class PushikoHttp2FrameLoggerTest {
    @Test
    fun logSettingsOncePerDirectionAtDebug() {
        val logger = mock<Logger>()
        val frameLogger = PushikoHttp2FrameLogger(logger, Level.DEBUG)
        frameLogger.logSettings(INBOUND, mock(), mock())
        frameLogger.logSettings(INBOUND, mock(), mock())
        frameLogger.logSettings(OUTBOUND, mock(), mock())
        frameLogger.logSettings(OUTBOUND, mock(), mock())
        verify(logger, times(1)).debug(any<String>(), isNull(), eq("INBOUND"), any())
        verify(logger, times(1)).debug(any<String>(), isNull(), eq("OUTBOUND"), any())
    }

    @Test
    fun isEnabled() {
        assertFalse(PushikoHttp2FrameLogger(mock(stubOnly = true), Level.INFO).isEnabled)
    }
}
