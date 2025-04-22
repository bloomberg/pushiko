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

package com.bloomberg.pushiko.fcm.oauth

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import java.time.Duration
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class OAuthRefreshBackOffTest {
    @Test
    fun accessTokenReference() {
        val token = AccessToken(FAKE_TOKEN, Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(600L)))
        val credentials = createCredentials(FAKE_TOKEN)
        assertEquals(token.tokenValue, credentials.accessToken.tokenValue)
        assertTrue(token.expiresInSeconds <= credentials.accessToken.expiresInSeconds)
    }

    @Test
    fun willStop() {
        val credentials = createCredentials(expiresInSeconds = -1L)
        val backOff = OAuthRefreshBackOff(credentials, 0.0, Duration.ofSeconds(1L), 0)
        backOff.reset()
        backOff.nextBackOffMillis()
        assertEquals(-1L, backOff.nextBackOffMillis())
    }

    @Test
    fun initialNextBackOffMillis() {
        val credentials = createCredentials(expiresInSeconds = 100L)
        val backOff = OAuthRefreshBackOff(credentials, 1.0, Duration.ofSeconds(1L), 0)
        val firstExpiresIn = credentials.accessToken.expiresInSeconds
        val intervalInSeconds = backOff.nextBackOffMillis() / 1_000L
        val secondExpiresIn = credentials.accessToken.expiresInSeconds
        assertTrue(intervalInSeconds <= firstExpiresIn)
        assertTrue(secondExpiresIn <= intervalInSeconds)
    }

    @Test
    fun willUseMinInterval() {
        val credentials = createCredentials(expiresInSeconds = 100L)
        val duration = Duration.ofSeconds(20L)
        val backOff = OAuthRefreshBackOff(credentials, 0.0, duration, 0)
        backOff.reset()
        assertEquals(duration.toMillis(), backOff.nextBackOffMillis())
    }

    @Test
    fun willAttemptAfterExpiryThenStop() {
        val credentials = createCredentials(expiresInSeconds = -1L)
        val duration = Duration.ofSeconds(20L)
        val backOff = OAuthRefreshBackOff(credentials, 2.0 / 3, duration, 2)
        backOff.reset()
        assertEquals(duration.toMillis(), backOff.nextBackOffMillis())
        assertEquals(duration.toMillis(), backOff.nextBackOffMillis())
        assertEquals(-1L, backOff.nextBackOffMillis())
    }

    @Test
    fun nextBackOffMillisDecays() {
        val credentials = createCredentials(expiresInSeconds = 90L)
        val backOff = OAuthRefreshBackOff(credentials, 2.0 / 3, Duration.ofSeconds(1L), 0)
        backOff.reset()
        assertTrue(backOff.nextBackOffMillis() <= TimeUnit.SECONDS.toMillis(60L))
    }

    private companion object {
        const val FAKE_TOKEN = "abc123"

        fun createCredentials(
            token: String = FAKE_TOKEN,
            expiresInSeconds: Long = 600L
        ): GoogleCredentials = GoogleCredentials.newBuilder().apply {
            accessToken = AccessToken(
                token,
                Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(expiresInSeconds))
            )
        }.build()
    }
}
