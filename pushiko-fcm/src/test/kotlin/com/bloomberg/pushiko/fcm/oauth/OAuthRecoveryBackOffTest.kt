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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class OAuthRecoveryBackOffTest {
    @Test
    fun intervalsIncreaseToCapAndReset() {
        val backOff = OAuthRecoveryBackOff(
            initialInterval = Duration.ofMillis(100L),
            maximumInterval = Duration.ofMillis(400L),
            jitter = { _, until -> until - 1L }
        )

        assertEquals(100L, backOff.nextBackOffMillis())
        assertEquals(200L, backOff.nextBackOffMillis())
        assertEquals(400L, backOff.nextBackOffMillis())
        assertEquals(400L, backOff.nextBackOffMillis())
        backOff.reset()
        assertEquals(100L, backOff.nextBackOffMillis())
    }

    @Test
    fun jitterCannotReduceIntervalBelowHalf() {
        val backOff = OAuthRecoveryBackOff(
            initialInterval = Duration.ofMillis(100L),
            maximumInterval = Duration.ofMillis(100L),
            jitter = { from, _ -> from }
        )

        assertEquals(50L, backOff.nextBackOffMillis())
    }

    @Test
    fun intervalsMustBePositiveAndOrdered() {
        assertFailsWith<IllegalArgumentException> {
            OAuthRecoveryBackOff(Duration.ZERO, Duration.ofMinutes(1L))
        }
        assertFailsWith<IllegalArgumentException> {
            OAuthRecoveryBackOff(Duration.ofMinutes(2L), Duration.ofMinutes(1L))
        }
    }
}
