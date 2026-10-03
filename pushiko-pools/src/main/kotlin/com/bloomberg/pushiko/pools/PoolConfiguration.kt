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

import javax.annotation.concurrent.ThreadSafe
import kotlin.time.Duration

@ThreadSafe
public data class WaterMarkScaleFactor(
    val low: Double = 1.0 / 3,
    val high: Double = 1.0
)

@ThreadSafe
public data class PoolConfiguration(
    val errorRateThreshold: Double,
    val fullScanPoolSize: Int,
    val maximumPendingAcquisitions: Int,
    val maximumSampledScan: Int,
    val maximumSize: Int,
    val minimumSize: Int,
    val name: String = "Pushiko.Pool",
    val reaperDelay: Duration,
    val summaryInterval: Duration
) {
    init {
        require(maximumSize in 1..MAXIMUM_SIZE) {
            "Maximum pool size must be within [1, $MAXIMUM_SIZE], got $maximumSize"
        }
        require(minimumSize in 0..maximumSize) {
            "Invalid pool size configuration min: $minimumSize max: $maximumSize"
        }
        require(maximumPendingAcquisitions > 0) {
            "Maximum pending acquisitions must be positive, got $maximumPendingAcquisitions"
        }
        require(errorRateThreshold in 0.0..1.0) {
            "Invalid error rate threshold: $errorRateThreshold"
        }
        require(fullScanPoolSize in 1..maximumSampledScan) {
            "Invalid scan bounds fullScanPoolSize: $fullScanPoolSize maximumSampledScan: $maximumSampledScan"
        }
    }

    public companion object {
        /** Largest supported pool size, bounding the eagerly allocated pool index. */
        public const val MAXIMUM_SIZE: Int = 1_000_000
    }
}
