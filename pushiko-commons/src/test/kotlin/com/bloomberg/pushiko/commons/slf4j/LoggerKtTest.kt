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

package com.bloomberg.pushiko.commons.slf4j

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.slf4j.Logger
import kotlin.test.assertEquals

internal class LoggerKtTest {
    @Test
    fun loggerThis() {
        Logger().run {
            assertEquals(LoggerKtTest::class.java.canonicalName, name)
        }
    }

    @Test
    fun actionIfDebugEnabled() {
        val logger = Logger()
        val action = mock<Logger.() -> Unit>()
        logger.ifDebugEnabled(action)
        verify(action, times(1)).invoke(same(logger))
    }

    @Test
    fun actionIfDebugNotEnabled() {
        val logger = object : Logger by Logger() {
            override fun isDebugEnabled() = false
        }
        val action = mock<Logger.() -> Unit>()
        logger.ifDebugEnabled(action)
        verify(action, never()).invoke(same(logger))
    }

    @Test
    fun actionIfInfoEnabled() {
        val logger = Logger()
        val action = mock<Logger.() -> Unit>()
        logger.ifInfoEnabled(action)
        verify(action, times(1)).invoke(same(logger))
    }

    @Test
    fun actionIfInfoNotEnabled() {
        val logger = object : Logger by Logger() {
            override fun isInfoEnabled() = false
        }
        val action = mock<Logger.() -> Unit>()
        logger.ifInfoEnabled(action)
        verify(action, never()).invoke(same(logger))
    }
}
