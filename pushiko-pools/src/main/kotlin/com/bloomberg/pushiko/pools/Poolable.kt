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

import javax.annotation.concurrent.NotThreadSafe
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@NotThreadSafe
public abstract class Poolable<out R : Any>(
    @JvmField
    @PublishedApi
    internal val value: R,
    private val ewmaAlpha: Double = 0.2,
    private val errorRateHalfLife: Duration = 30.seconds,
    private val nanoTime: () -> Long = System::nanoTime
) {
    init {
        require(ewmaAlpha > 0.0 && ewmaAlpha < 1.0) {
            "EWMA alpha must be in range (0.0, 1.0), got $ewmaAlpha"
        }
        require(errorRateHalfLife.isPositive() && errorRateHalfLife.isFinite()) {
            "EWMA error rate half-life must be positive and finite, got $errorRateHalfLife"
        }
    }

    private val errorRateHalfLifeNanos = errorRateHalfLife.inWholeNanoseconds.toDouble()
    private var lastOutcomeNanos = 0L

    public var allocatedPermits: Int = 0
        private set

    public abstract val maximumPermits: Int

    public abstract val isAlive: Boolean

    public abstract val isCanAcquire: Boolean

    public abstract val isShouldAcquire: Boolean

    public open fun isError(throwable: Throwable): Boolean = !isAlive

    private var outcomeSamples = 0

    internal var errorRate: Double = 0.0
        private set

    internal var meanHoldNanos: Double = 0.0
        private set

    public fun acquirePermit(): Poolable<R> = apply {
        ++allocatedPermits
    }

    public fun releasePermit() {
        --allocatedPermits
    }

    public fun recordOutcome(holdNanos: Long, wasSuccess: Boolean) {
        val errorSample = if (wasSuccess) { 0.0 } else { 1.0 }
        val holdSample = holdNanos.toDouble()
        val now = nanoTime()
        errorRate = ewmaAlpha * errorSample + (1.0 - ewmaAlpha) * decayed(errorRate, now - lastOutcomeNanos)
        meanHoldNanos = if (outcomeSamples == 0) {
            holdSample
        } else {
            ewmaAlpha * holdSample + (1.0 - ewmaAlpha) * meanHoldNanos
        }
        lastOutcomeNanos = now
        ++outcomeSamples
    }

    internal fun currentErrorRate(): Double = if (outcomeSamples == 0) {
        0.0
    } else {
        decayed(errorRate, nanoTime() - lastOutcomeNanos)
    }

    private fun decayed(value: Double, elapsedNanos: Long): Double = if (elapsedNanos <= 0L) {
        value
    } else {
        value * 2.0.pow(-elapsedNanos.toDouble() / errorRateHalfLifeNanos)
    }

    public open suspend fun summarize(appendable: Appendable) {
        appendable.appendLine("    Error rate: ${currentErrorRate()}")
            .appendLine("    Mean hold (ns): $meanHoldNanos")
    }
}
