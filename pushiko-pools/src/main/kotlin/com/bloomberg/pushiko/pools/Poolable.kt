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

/**
 * A resource and its pool-specific capacity and health state.
 *
 * Property getters, [isError], and [onAvailabilityChangedListenerChanged] are invoked on the pool's control
 * dispatcher. Implementations must return promptly, must not block and must not throw. [summarize] may suspend, but
 * must not block its calling thread.
 */
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

    @Volatile
    private var availabilityChangedListener: (() -> Unit)? = null

    public var allocatedPermits: Int = 0
        private set

    public abstract val maximumPermits: Int

    /**
     * Whether this object may serve further acquisitions. Once `false`, existing permits are allowed to finish.
     * Implementations must call [notifyAvailabilityChanged] when this changes from `true` to `false`.
     */
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
        check(tryAcquirePermit()) { "No permit is available" }
    }

    internal fun tryAcquirePermit(): Boolean {
        if (!isCanAcquire || !isAlive) {
            return false
        }
        check(allocatedPermits < Int.MAX_VALUE) { "Allocated permit count overflow" }
        ++allocatedPermits
        return true
    }

    public fun releasePermit() {
        check(allocatedPermits > 0) { "Cannot release an unallocated permit" }
        --allocatedPermits
    }

    /**
     * Notifies the pool that capacity may have become available without a permit being released.
     */
    protected fun notifyAvailabilityChanged() {
        availabilityChangedListener?.invoke()
    }

    /**
     * Invoked when this object is attached to or detached from a pool's availability notifications.
     * Implementations that bridge an external notification source should install their callback when [isAttached] is
     * true and remove it when false. The callback should invoke [notifyAvailabilityChanged] so that a notification
     * already in flight becomes a no-op after detachment.
     */
    protected open fun onAvailabilityChangedListenerChanged(isAttached: Boolean): Unit = Unit

    internal fun setAvailabilityChangedListener(listener: () -> Unit) {
        availabilityChangedListener = listener
        onAvailabilityChangedListenerChanged(true)
    }

    internal fun clearAvailabilityChangedListener() {
        availabilityChangedListener = null
        onAvailabilityChangedListenerChanged(false)
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
