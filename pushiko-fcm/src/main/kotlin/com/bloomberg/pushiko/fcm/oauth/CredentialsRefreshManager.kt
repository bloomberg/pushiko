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

import com.bloomberg.pushiko.commons.slf4j.Logger
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.Retryable
import com.google.auth.oauth2.AccessToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.annotation.concurrent.ThreadSafe
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@ThreadSafe
internal class CredentialsRefreshManager(
    private val credentials: ServiceAccountCredentials,
    dispatcher: CoroutineDispatcher,
    private val backOff: BackOff = OAuthRefreshBackOff(credentials)
) {
    private val logger = Logger()
    private val iterator = iterator {
        while (true) {
            try {
                credentials.run {
                    refresh()
                    logger.info(
                        "Google OAuth token was refreshed for {}, expires in at most {}s",
                        credentials.projectId,
                        accessToken.expiresInSeconds
                    )
                    yield(RefreshResult.Success(accessToken))
                }
            } catch (e: IOException) {
                if (e.isPermanentFailure()) {
                    logger.error("OAuth refresh failed permanently for {}: {}", credentials.projectId, e.message)
                    yield(RefreshResult.PermanentFailure(e))
                } else {
                    logger.warn("No new Google OAuth token for ${credentials.projectId} is available", e)
                    yield(RefreshResult.RetryableFailure(e))
                }
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        while (scope.isActive) {
            when (val result = iterator.next()) {
                is RefreshResult.Success -> break
                is RefreshResult.RetryableFailure -> Thread.sleep(initialDelay.toMillis())
                is RefreshResult.PermanentFailure -> throw result.exception
            }
        }
        scope.launch { keepAlive() }.also {
            check(it.isActive) { "OAuth session is not being kept alive" }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun keepAlive() {
        while (currentCoroutineContext().isActive) {
            val interval = backOff.nextBackOffMillis()
            if (interval < 0L) {
                IllegalStateException(
                    "Failed to refresh ${credentials.projectId} Google OAuth access token and back-off has already stopped"
                ).let {
                    logger.error(it.message)
                    throw it
                }
            }
            logger.info(
                "Scheduling task to refresh {} access token, delayed {}s",
                credentials.projectId,
                TimeUnit.MILLISECONDS.toSeconds(interval)
            )
            delay(interval)
            when (iterator.next()) {
                is RefreshResult.Success -> backOff.reset()
                is RefreshResult.RetryableFailure,
                is RefreshResult.PermanentFailure -> Unit
            }
        }
        logger.info("OAuth keep-alive stopped: {} manager has been shut down", credentials.projectId)
    }

    private sealed interface RefreshResult {
        data class Success(val accessToken: AccessToken) : RefreshResult

        data class RetryableFailure(val exception: IOException) : RefreshResult

        data class PermanentFailure(val exception: IOException) : RefreshResult
    }

    private fun IOException.isPermanentFailure(): Boolean = this is Retryable && !isRetryable

    private companion object {
        val initialDelay: Duration = Duration.ofSeconds(2L)
    }
}
