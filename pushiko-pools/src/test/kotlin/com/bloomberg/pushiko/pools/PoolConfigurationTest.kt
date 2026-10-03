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

package com.bloomberg.pushiko.pools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class PoolConfigurationTest {
    @Test
    fun rejectsNonPositiveOrInfiniteShutdownTimeout() {
        assertThrows<IllegalArgumentException> {
            poolConfiguration(shutdownTimeout = Duration.ZERO)
        }
        assertThrows<IllegalArgumentException> {
            poolConfiguration(shutdownTimeout = (-1L).seconds)
        }
        assertThrows<IllegalArgumentException> {
            poolConfiguration(shutdownTimeout = Duration.INFINITE)
        }
    }

    @Test
    fun acceptsEqualNonZeroMinMax() {
        assertDoesNotThrow {
            poolConfiguration(
                maximumSize = 1,
                maximumPendingAcquisitions = 1,
                minimumSize = 1,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes
            )
        }
    }

    @Test
    fun acceptsMinMax() {
        assertDoesNotThrow {
            poolConfiguration(
                maximumSize = 2,
                maximumPendingAcquisitions = 1,
                minimumSize = 1,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes
            )
        }
    }

    @Test
    fun zeroMinMax() {
        assertThrows<IllegalArgumentException> {
            poolConfiguration(
                maximumSize = 0,
                maximumPendingAcquisitions = 1,
                minimumSize = 0,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes
            )
        }
    }

    @Test
    fun illegalMinMax() {
        assertThrows<IllegalArgumentException> {
            poolConfiguration(
                maximumSize = 0,
                maximumPendingAcquisitions = 1,
                minimumSize = 1,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes
            )
        }
    }

    @Test
    fun rejectsNonPositiveMaximumPendingAcquisitions() {
        listOf(-1, 0).forEach {
            assertThrows<IllegalArgumentException> {
                poolConfiguration(maximumPendingAcquisitions = it)
            }
        }
    }

    @Test
    fun acceptsMaximumSupportedPoolSize() {
        assertDoesNotThrow {
            poolConfiguration(maximumSize = PoolConfiguration.MAXIMUM_SIZE)
        }
    }

    @Test
    fun rejectsPoolSizeAboveOperationalLimit() {
        assertThrows<IllegalArgumentException> {
            poolConfiguration(maximumSize = PoolConfiguration.MAXIMUM_SIZE + 1)
        }
    }

    @Test
    fun acceptsErrorRateThresholdWithinRange() {
        assertDoesNotThrow {
            poolConfiguration(
                maximumSize = 1,
                maximumPendingAcquisitions = 1,
                minimumSize = 1,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes,
                errorRateThreshold = 0.0
            )
            poolConfiguration(
                maximumSize = 1,
                maximumPendingAcquisitions = 1,
                minimumSize = 1,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes,
                errorRateThreshold = 1.0
            )
        }
    }

    @Test
    fun rejectsErrorRateThresholdBelowZero() {
        assertThrows<IllegalArgumentException> {
            poolConfiguration(
                maximumSize = 1,
                maximumPendingAcquisitions = 1,
                minimumSize = 1,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes,
                errorRateThreshold = -0.1
            )
        }
    }

    @Test
    fun rejectsErrorRateThresholdAboveOne() {
        assertThrows<IllegalArgumentException> {
            poolConfiguration(
                maximumSize = 1,
                maximumPendingAcquisitions = 1,
                minimumSize = 1,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes,
                errorRateThreshold = 1.1
            )
        }
    }

    @Test
    fun acceptsCustomScanBounds() {
        assertDoesNotThrow {
            poolConfiguration(
                maximumSize = 1,
                maximumPendingAcquisitions = 1,
                minimumSize = 1,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 5,
                maximumSampledScan = 5
            )
        }
    }

    @Test
    fun rejectsNonPositiveFullScanPoolSize() {
        assertThrows<IllegalArgumentException> {
            poolConfiguration(
                maximumSize = 1,
                maximumPendingAcquisitions = 1,
                minimumSize = 1,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 0
            )
        }
    }

    @Test
    fun rejectsFullScanPoolSizeExceedingMaxSampledScan() {
        assertThrows<IllegalArgumentException> {
            poolConfiguration(
                maximumSize = 1,
                maximumPendingAcquisitions = 1,
                minimumSize = 1,
                reaperDelay = 1L.minutes,
                summaryInterval = 5L.minutes,
                fullScanPoolSize = 21,
                maximumSampledScan = 20
            )
        }
    }
}
