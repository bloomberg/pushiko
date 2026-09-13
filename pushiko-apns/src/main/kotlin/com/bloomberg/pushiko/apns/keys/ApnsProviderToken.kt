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

package com.bloomberg.pushiko.apns.keys

import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val BEARER_PREFIX = "bearer "
private val DEFAULT_TOKEN_REFRESH_INTERVAL = 50L.minutes

internal class ApnsProviderToken(
    private val signingKey: ApnsSigningKey,
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime,
    tokenRefreshInterval: Duration = DEFAULT_TOKEN_REFRESH_INTERVAL
) {
    private val tokenRefreshIntervalNanos = tokenRefreshInterval.inWholeNanoseconds.also {
        require(tokenRefreshInterval.isPositive() && tokenRefreshInterval <= DEFAULT_TOKEN_REFRESH_INTERVAL) {
            "APNs provider token refresh interval must be positive and no longer than 50 minutes"
        }
    }

    @Volatile
    private var cachedAuthorization: CachedAuthorization? = null

    internal fun currentAuthorization(): String {
        val now = nanoTime()
        cachedAuthorization?.takeIf { it.isFresh(now) }?.let { return it.value }
        return synchronized(this) {
            val lockedNow = nanoTime()
            cachedAuthorization?.takeIf { it.isFresh(lockedNow) }?.value ?: createAuthorization(lockedNow).also {
                cachedAuthorization = it
            }.value
        }
    }

    internal fun invalidate(authorization: String) {
        synchronized(this) {
            if (cachedAuthorization?.value === authorization) {
                cachedAuthorization = null
            }
        }
    }

    private fun createAuthorization(createdAtNanos: Long): CachedAuthorization {
        val authorization = BEARER_PREFIX + signingKey.createProviderToken(clock.instant())
        return CachedAuthorization(authorization, createdAtNanos)
    }

    private inner class CachedAuthorization(
        val value: String,
        private val createdAtNanos: Long
    ) {
        fun isFresh(nowNanos: Long): Boolean = nowNanos - createdAtNanos in 0 until tokenRefreshIntervalNanos
    }
}
