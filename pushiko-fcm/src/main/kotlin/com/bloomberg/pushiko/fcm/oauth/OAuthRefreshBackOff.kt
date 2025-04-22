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

import com.bloomberg.pushiko.commons.strings.commonPluralSuffix
import com.bloomberg.pushiko.commons.slf4j.Logger
import com.google.auth.oauth2.OAuth2Credentials
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import java.time.Duration
import javax.annotation.concurrent.ThreadSafe

private const val DEFAULT_MULTIPLIER = 1.0 / 3

@ThreadSafe
internal class OAuthRefreshBackOff(
    private val credentials: OAuth2Credentials,
    private val multiplier: Double = DEFAULT_MULTIPLIER,
    private val minInterval: Duration = defaultMinInterval,
    private val maxNumberOfAttemptsAfterExpiration: Int = Int.MAX_VALUE
) : BackOff {
    private val logger = Logger()
    private val remainingPostExpirationAttempts = atomic(maxNumberOfAttemptsAfterExpiration)

    override fun reset() {
        remainingPostExpirationAttempts.value = maxNumberOfAttemptsAfterExpiration
        logger.info("Back-off was reset")
    }

    override fun nextBackOffMillis() = nextInterval().also {
        if (it.isNegative) {
            logger.error("No more back-off attempts remain, operation should not be retried")
        } else {
            logger.info("Granted next back-off of {}s", it.seconds)
        }
    }.toMillis()

    private fun intervalDerivedFromCredentials() = Duration.ofSeconds(
        (credentials.accessToken.expiresInSeconds * multiplier).toLong())

    private fun nextInterval() = if (hasAccessTokenExpired()) {
        logger.error("Google OAuth access token has already expired")
        val previous = remainingPostExpirationAttempts.getAndUpdate {
            if (it > 0) { it - 1 } else { it }
        }
        if (previous > 0) {
            val next = previous - 1
            logger.warn("Google OAuth access token has {} remaining refresh attempt{}", next,
                next.commonPluralSuffix())
            minInterval
        } else {
            logger.error("Google OAuth access token refresh back-off will now cease")
            stop
        }
    } else {
        val duration = intervalDerivedFromCredentials()
        if (minInterval > duration) { minInterval } else { duration }
    }

    private fun hasAccessTokenExpired() = credentials.accessToken.expiresInSeconds < 1L

    internal companion object {
        val defaultMinInterval: Duration = Duration.ofSeconds(10L)
        val stop: Duration = Duration.ofMillis(BackOff.STOP)
    }
}
