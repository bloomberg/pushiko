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

package com.bloomberg.pushiko.http.netty.http2

import io.netty.channel.Channel
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.slf4j.Logger

internal class SettingsLogLimiterTest {
    @Test
    fun logsOnlyFirstChange() {
        val logger = mock<Logger>()
        val channel = mock<Channel>()
        SettingsLogLimiter(logger).apply {
            logChange(100L, 50L, channel)
            logChange(50L, 25L, channel)
            logChange(25L, 100L, channel)
        }

        verify(logger, times(1)).debug(
            eq(
                "Peer changed SETTINGS_MAX_CONCURRENT_STREAMS from: {} to: {} channel: {}; " +
                    "further changes will not be logged"
            ),
            eq(100L),
            eq(50L),
            eq(channel)
        )
    }
}
