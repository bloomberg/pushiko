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

package com.bloomberg.pushiko.fcm.oauth

import java.time.Duration
import kotlin.random.Random

internal class OAuthRecoveryBackOff(
    initialInterval: Duration = DEFAULT_INITIAL_INTERVAL,
    maximumInterval: Duration = DEFAULT_MAXIMUM_INTERVAL,
    private val jitter: (Long, Long) -> Long = { from, until -> Random.nextLong(from, until) }
) : BackOff {
    private val initialIntervalMillis = initialInterval.toMillis()
    private val maximumIntervalMillis = maximumInterval.toMillis()
    private var nextMaximumIntervalMillis = initialIntervalMillis

    init {
        require(initialIntervalMillis > 0L) { "Initial OAuth recovery interval must be positive" }
        require(maximumIntervalMillis >= initialIntervalMillis) {
            "Maximum OAuth recovery interval must not be shorter than the initial interval"
        }
        require(maximumIntervalMillis < Long.MAX_VALUE) { "Maximum OAuth recovery interval is too long" }
    }

    override fun nextBackOffMillis(): Long {
        val maximum = nextMaximumIntervalMillis
        val minimum = maximum / 2L
        val interval = jitter(minimum, maximum + 1L)
        require(interval in minimum..maximum) { "OAuth recovery jitter returned an invalid interval" }
        nextMaximumIntervalMillis = maximum.doubledAtMost(maximumIntervalMillis)
        return interval
    }

    override fun reset() {
        nextMaximumIntervalMillis = initialIntervalMillis
    }

    private fun Long.doubledAtMost(maximum: Long): Long = if (this >= maximum / 2L) {
        maximum
    } else {
        minOf(this * 2L, maximum)
    }

    private companion object {
        val DEFAULT_INITIAL_INTERVAL: Duration = Duration.ofMinutes(1L)
        val DEFAULT_MAXIMUM_INTERVAL: Duration = Duration.ofHours(1L)
    }
}
