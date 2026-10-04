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

import com.bloomberg.pushiko.http.HttpClientProperties.Companion.OptionalHttpProperties
import com.bloomberg.pushiko.pools.PoolConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class HttpClientPropertiesTest {
    @Test
    fun rejectsNonPositiveDefaultMaximumConcurrentStreams() {
        listOf(-1L, 0L).forEach {
            assertFailsWith<IllegalArgumentException> {
                OptionalHttpProperties(defaultMaximumConcurrentStreams = it)
            }
        }
    }

    @Test
    fun rejectsConnectionCountOutsideSupportedRange() {
        listOf(0, PoolConfiguration.MAXIMUM_SIZE + 1).forEach {
            assertFailsWith<IllegalArgumentException> {
                OptionalHttpProperties(maximumConnections = it)
            }
        }
    }

    @Test
    fun acceptsMaximumSupportedConnectionCountWithoutOverflowingRetries() {
        val properties = OptionalHttpProperties(maximumConnections = PoolConfiguration.MAXIMUM_SIZE)

        assertEquals(3_000_000, properties.maximumRequestRetries)
    }

    @Test
    fun rejectsNegativeMinimumConnections() {
        assertFailsWith<IllegalArgumentException> {
            OptionalHttpProperties(minimumConnections = -1)
        }
    }

    @Test
    fun rejectsNegativeMaximumRequestRetries() {
        assertFailsWith<IllegalArgumentException> {
            OptionalHttpProperties().copy(maximumRequestRetries = -1)
        }
    }
}
