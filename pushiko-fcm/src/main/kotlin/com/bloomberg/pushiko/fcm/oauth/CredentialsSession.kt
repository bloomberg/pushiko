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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.bloomberg.pushiko.fcm.oauth

import com.bloomberg.pushiko.commons.slf4j.Logger
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.ServiceAccountCredentials
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.annotation.concurrent.ThreadSafe

private fun String.bearer() = "Bearer $this"
private fun String.fcmSendPath() = "/v1/projects/%s/messages:send".format(Locale.US, this)
// Match google-auth's default expiration margin so refreshIfExpired() blocks for a replacement once a token is unsafe.
private const val AUTHORIZATION_EXPIRY_SKEW_MILLIS = 3L * 60L * 1_000L

@ThreadSafe
internal class CredentialsSession private constructor(
    private val credentials: ServiceAccountCredentials,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock
) : Session {
    override val projectId: String = requireNotNull(credentials.projectId) {
        "FCM service-account credentials have no project ID"
    }.also {
        require(it.isNotBlank()) { "FCM service-account credentials have a blank project ID" }
    }

    override val sendPath = projectId.fcmSendPath()

    private val logger = Logger()
    private val credentialsRefresher = CredentialsRefreshManager(credentials, dispatcher)

    @JvmSynthetic
    override suspend fun currentAuthorization(): String = credentials.accessToken
        ?.takeIf(::isFresh)
        ?.tokenValue
        ?.bearer()
        ?: withContext(dispatcher) {
            credentials.refreshIfExpired()
            requireFreshTokenValue(credentials.accessToken).bearer()
        }

    override suspend fun joinStart() {
        credentialsRefresher.joinStart()
        currentAuthorization()
    }

    override fun close() {
        logger.info("Closing credential session, id: {}", projectId)
        credentialsRefresher.stop()
    }

    private fun isFresh(accessToken: AccessToken): Boolean {
        val expiration = accessToken.expirationTime
        return !accessToken.tokenValue.isNullOrBlank() &&
            expiration != null &&
            expiration.time > clock.millis() + AUTHORIZATION_EXPIRY_SKEW_MILLIS
    }

    private fun requireFreshTokenValue(accessToken: AccessToken?): String {
        val token = requireNotNull(accessToken) {
            "FCM session '$projectId' has no access token. Call joinStart() first"
        }
        val tokenValue = requireNotNull(token.tokenValue) {
            "FCM session '$projectId' has a null access token"
        }
        require(tokenValue.isNotBlank()) {
            "FCM session '$projectId' has a blank access token"
        }
        val expiration = requireNotNull(token.expirationTime) {
            "FCM session '$projectId' access token has no expiration"
        }
        check(expiration.time > clock.millis() + AUTHORIZATION_EXPIRY_SKEW_MILLIS) {
            "FCM session '$projectId' access token is expired or expires imminently"
        }
        return tokenValue
    }

    internal companion object {
        @JvmSynthetic
        internal fun create(
            credentials: ServiceAccountCredentials,
            dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
            clock: Clock = Clock.systemUTC()
        ): CredentialsSession = CredentialsSession(credentials, dispatcher, clock)
    }
}
