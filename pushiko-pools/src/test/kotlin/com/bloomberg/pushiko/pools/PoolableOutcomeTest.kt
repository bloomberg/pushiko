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

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class PoolableOutcomeTest {
    private companion object {
        const val TOLERANCE = 1e-9
    }

    private class LivenessPoolable(
        override val isAlive: Boolean
    ) : Poolable<Any>(Any()) {
        override val maximumPermits: Int = 1
        override val isCanAcquire: Boolean = true
        override val isShouldAcquire: Boolean = true
    }

    @Test
    fun failureWhilePooledObjectAliveIsNotItsError() {
        assertFalse(LivenessPoolable(isAlive = true).isError(RuntimeException("boom")))
    }

    @Test
    fun failureAfterPooledObjectDiesIsItsError() {
        assertTrue(LivenessPoolable(isAlive = false).isError(RuntimeException("boom")))
    }

    @Test
    fun startsWithZeroedOutcomes() {
        val poolable = AnyPoolable()
        assertEquals(0.0, poolable.errorRate)
        assertEquals(0.0, poolable.meanHoldNanos)
    }

    @Test
    fun firstFailureRampsErrorRateByAlphaAndSeedsHoldTime() {
        val poolable = AnyPoolable().apply { recordOutcome(holdNanos = 1_000L, wasSuccess = false) }
        assertEquals(0.2, poolable.errorRate, TOLERANCE)
        assertEquals(1_000.0, poolable.meanHoldNanos)
    }

    @Test
    fun singleEarlyFailureStaysBelowDefaultThreshold() {
        val poolable = AnyPoolable().apply { recordOutcome(1_000L, wasSuccess = false) }
        assertTrue(poolable.currentErrorRate() <= 0.5)
    }

    @Test
    fun sustainedSuccessKeepsErrorRateAtZero() {
        val poolable = AnyPoolable().apply { repeat(10) { recordOutcome(1_000L, wasSuccess = true) } }
        assertEquals(0.0, poolable.errorRate)
    }

    @Test
    fun sustainedFailureDrivesErrorRateTowardsOne() {
        val poolable = AnyPoolable().apply { repeat(50) { recordOutcome(1_000L, wasSuccess = false) } }
        assertEquals(1.0, poolable.errorRate, 1e-3)
    }

    @Test
    fun failureThenSuccessBlendsByAlpha() {
        val poolable = AnyPoolable().apply {
            recordOutcome(1_000L, wasSuccess = false)
            recordOutcome(1_000L, wasSuccess = true)
        }
        assertEquals(0.16, poolable.errorRate, TOLERANCE)
    }

    @Test
    fun recoveryDecaysAStaleErrorRate() {
        val poolable = AnyPoolable().apply {
            repeat(20) { recordOutcome(1_000L, wasSuccess = false) }
            repeat(20) { recordOutcome(1_000L, wasSuccess = true) }
        }
        assert(poolable.errorRate < 0.05) { "errorRate did not decay: ${poolable.errorRate}" }
    }

    @Test
    fun meanHoldTracksRecentDurations() {
        val poolable = AnyPoolable().apply {
            recordOutcome(1_000L, wasSuccess = true)
            recordOutcome(2_000L, wasSuccess = true)
        }
        assertEquals(1_200.0, poolable.meanHoldNanos, TOLERANCE)
    }

    @Test
    fun ewmaAlphaAtZeroIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AnyPoolable(ewmaAlpha = 0.0)
        }
    }

    @Test
    fun ewmaAlphaAtOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AnyPoolable(ewmaAlpha = 1.0)
        }
    }

    @Test
    fun ewmaAlphaBelowZeroIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AnyPoolable(ewmaAlpha = -0.1)
        }
    }

    @Test
    fun ewmaAlphaAboveOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AnyPoolable(ewmaAlpha = 1.5)
        }
    }

    @Test
    fun ewmaAlphaStrictlyWithinBoundsIsAccepted() {
        AnyPoolable(ewmaAlpha = 0.2)
        AnyPoolable(ewmaAlpha = 0.5)
        AnyPoolable(ewmaAlpha = 0.99)
    }

    @Test
    fun errorRateDecaysTowardsZeroWhileIdle() {
        var now = 0L
        val halfLife = 1.seconds
        val poolable = AnyPoolable(errorRateHalfLife = halfLife, nanoTime = { now })
        poolable.recordOutcome(1_000L, wasSuccess = false)
        assertEquals(0.2, poolable.currentErrorRate(), TOLERANCE)
        now = halfLife.inWholeNanoseconds
        assertEquals(0.1, poolable.currentErrorRate(), TOLERANCE)
        now = 2 * halfLife.inWholeNanoseconds
        assertEquals(0.05, poolable.currentErrorRate(), TOLERANCE)
    }

    @Test
    fun idleDecayIsAppliedBeforeBlendingTheNextSample() {
        var now = 0L
        val halfLife = 1.seconds
        val poolable = AnyPoolable(errorRateHalfLife = halfLife, nanoTime = { now })
        poolable.recordOutcome(1_000L, wasSuccess = false)
        now = 2 * halfLife.inWholeNanoseconds
        poolable.recordOutcome(1_000L, wasSuccess = true)
        assertEquals(0.04, poolable.currentErrorRate(), TOLERANCE)
    }

    @Test
    fun errorRateHalfLifeAtOrBelowZeroIsRejected() {
        assertFailsWith<IllegalArgumentException> { AnyPoolable(errorRateHalfLife = 0.seconds) }
        assertFailsWith<IllegalArgumentException> { AnyPoolable(errorRateHalfLife = (-1).seconds) }
    }

    @Test
    fun infiniteErrorRateHalfLifeIsRejected() {
        assertFailsWith<IllegalArgumentException> { AnyPoolable(errorRateHalfLife = Duration.INFINITE) }
    }
}
