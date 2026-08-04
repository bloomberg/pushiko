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

package com.bloomberg.pushiko.pools

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Builds a [PoolConfiguration] with sensible defaults for every field so that tests need only override what is
 * relevant to them. The production [PoolConfiguration] deliberately requires the tuning knobs (error-rate threshold and
 * scan bounds) so that callers make a considered choice; those default policy values live here, in test scaffolding,
 * rather than being baked into the production type. This still invokes the real constructor, so its validation runs.
 */
@Suppress("Detekt.LongParameterList")
internal fun poolConfiguration(
    errorRateThreshold: Double = 0.5,
    fullScanPoolSize: Int = 10,
    maximumPendingAcquisitions: Int = 1_000,
    maximumSampledScan: Int = 20,
    maximumSize: Int = 1,
    minimumSize: Int = 1,
    name: String = "Pushiko.Pool",
    reaperDelay: Duration = 10L.minutes,
    summaryInterval: Duration = 5L.minutes
) = PoolConfiguration(
    errorRateThreshold = errorRateThreshold,
    fullScanPoolSize = fullScanPoolSize,
    maximumPendingAcquisitions = maximumPendingAcquisitions,
    maximumSampledScan = maximumSampledScan,
    maximumSize = maximumSize,
    minimumSize = minimumSize,
    name = name,
    reaperDelay = reaperDelay,
    summaryInterval = summaryInterval
)
