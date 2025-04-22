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

package com.bloomberg.pushiko.pool

import javax.annotation.concurrent.ThreadSafe
import kotlin.time.Duration

private const val HIGH_WATERMARK_SCALE_FACTOR: Double = 1.0
private const val LOW_WATERMARK_SCALE_FACTOR: Double = 1.0 / 3

@ThreadSafe
data class WaterMarkScaleFactor(
    val low: Double = LOW_WATERMARK_SCALE_FACTOR,
    val high: Double = HIGH_WATERMARK_SCALE_FACTOR
)

@ThreadSafe
data class PoolConfiguration(
    val acquisitionAttemptsThreshold: Int,
    val maximumPendingAcquisitions: Int,
    val maximumSize: Int,
    val minimumSize: Int,
    val name: String = "Pushiko.Pool",
    val reaperDelay: Duration,
    val summaryInterval: Duration
) {
    init {
        require(minimumSize in 1..maximumSize || minimumSize == 0 && maximumSize > 0) {
            "Invalid pool size configuration min: $minimumSize max: $maximumSize"
        }
    }
}
