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

import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Test
import org.slf4j.event.Level
import kotlin.test.assertSame

internal class LoggingKtTest {
    @Test
    fun traceToTrace() {
        assertSame(LogLevel.TRACE, Level.TRACE.nettyLevel())
    }

    @Test
    fun debugToDebug() {
        assertSame(LogLevel.DEBUG, Level.DEBUG.nettyLevel())
    }

    @Test
    fun infoToInfo() {
        assertSame(LogLevel.INFO, Level.INFO.nettyLevel())
    }

    @Test
    fun warnToWarn() {
        assertSame(LogLevel.WARN, Level.WARN.nettyLevel())
    }

    @Test
    fun errorToError() {
        assertSame(LogLevel.ERROR, Level.ERROR.nettyLevel())
    }
}
