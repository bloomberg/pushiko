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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
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

    @Test
    fun acceptsMillisecondPrecisionDurations() {
        val properties = OptionalHttpProperties(
            connectionAcquisitionTimeout = 1L.milliseconds,
            connectionFuzzInterval = 1L.milliseconds,
            connectTimeout = 1L.milliseconds,
            idleConnectionInterval = 1L.milliseconds,
            maximumConnectionAge = 1L.milliseconds
        ).copy(userTimeout = 1L.milliseconds)

        assertEquals(1L.milliseconds, properties.connectTimeout)
        assertEquals(1L.milliseconds, properties.userTimeout)
    }

    @Test
    fun validatesConnectAndUserTimeoutIntegerMillisecondBounds() {
        val maximumTimeout = Int.MAX_VALUE.toLong().milliseconds
        val properties = OptionalHttpProperties(connectTimeout = maximumTimeout)
            .copy(userTimeout = maximumTimeout)

        assertEquals(maximumTimeout, properties.connectTimeout)
        assertEquals(maximumTimeout, properties.userTimeout)

        val invalidTimeouts = listOf(
            Duration.ZERO,
            (-1L).milliseconds,
            1L.nanoseconds,
            (Int.MAX_VALUE.toLong() + 1L).milliseconds,
            INFINITE
        )
        invalidTimeouts.forEach { timeout ->
            assertFailsWith<IllegalArgumentException> {
                OptionalHttpProperties(connectTimeout = timeout)
            }
            assertFailsWith<IllegalArgumentException> {
                OptionalHttpProperties().copy(userTimeout = timeout)
            }
        }
    }

    @Test
    fun validatesConnectionFuzzInterval() {
        listOf(Duration.ZERO, 1L.milliseconds).forEach {
            assertEquals(it, OptionalHttpProperties(connectionFuzzInterval = it).connectionFuzzInterval)
        }

        listOf((-1L).milliseconds, 1L.nanoseconds, INFINITE).forEach {
            assertFailsWith<IllegalArgumentException> {
                OptionalHttpProperties(connectionFuzzInterval = it)
            }
        }
    }

    @Test
    fun validatesIdleConnectionInterval() {
        listOf(Duration.ZERO, (-1L).milliseconds, 1L.nanoseconds, INFINITE).forEach {
            assertFailsWith<IllegalArgumentException> {
                OptionalHttpProperties(idleConnectionInterval = it)
            }
        }
    }

    @Test
    fun validatesMaximumConnectionAge() {
        listOf(1L.milliseconds, INFINITE).forEach {
            assertEquals(it, OptionalHttpProperties(maximumConnectionAge = it).maximumConnectionAge)
        }

        listOf(Duration.ZERO, (-1L).milliseconds, 1L.nanoseconds, -INFINITE).forEach {
            assertFailsWith<IllegalArgumentException> {
                OptionalHttpProperties(maximumConnectionAge = it)
            }
        }
    }

    @Test
    fun validatesConnectionAcquisitionTimeout() {
        listOf(1L.nanoseconds, INFINITE).forEach {
            assertEquals(it, OptionalHttpProperties(connectionAcquisitionTimeout = it).connectionAcquisitionTimeout)
        }

        listOf(Duration.ZERO, (-1L).nanoseconds, -INFINITE).forEach {
            assertFailsWith<IllegalArgumentException> {
                OptionalHttpProperties(connectionAcquisitionTimeout = it)
            }
        }
    }

    @Test
    fun preservesDisabledPoolMaintenanceIntervals() {
        val properties = OptionalHttpProperties(reaperDelay = INFINITE, summaryInterval = INFINITE)

        assertEquals(INFINITE, properties.reaperDelay)
        assertEquals(INFINITE, properties.summaryInterval)
    }
}
