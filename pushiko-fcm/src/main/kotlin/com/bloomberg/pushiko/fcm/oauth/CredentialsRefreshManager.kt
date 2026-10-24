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
import kotlinx.atomicfu.atomic
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

@ThreadSafe
internal class CredentialsRefreshManager(
    private val credentials: ServiceAccountCredentials,
    dispatcher: CoroutineDispatcher,
    private val backOff: BackOff = OAuthRefreshBackOff(credentials),
    private val recoveryBackOff: BackOff = OAuthRecoveryBackOff()
) {
    private val logger = Logger()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val permanentFailure = atomic<IOException?>(null)
    private val startup = scope.async(start = CoroutineStart.LAZY) {
        while (currentCoroutineContext().isActive) {
            when (val result = refreshOnce()) {
                is RefreshResult.Success -> {
                    logger.info(
                        "Startup OAuth refresh succeeded for {}, token expires in at most {}s",
                        credentials.projectId,
                        result.accessToken.expiresInSeconds
                    )
                    return@async result.accessToken
                }
                is RefreshResult.RetryableFailure -> {
                    logger.warn(
                        "Startup OAuth refresh failed for {}, retrying in {}s: {}",
                        credentials.projectId,
                        initialDelay.seconds,
                        result.exception.message
                    )
                    delay(initialDelay.toMillis())
                }
                is RefreshResult.PermanentFailure -> {
                    logger.error(
                        "Startup OAuth refresh failed permanently for {}: {}",
                        credentials.projectId,
                        result.exception.message
                    )
                    throw result.exception
                }
            }
        }
        error("OAuth startup finished without obtaining an access token")
    }
    private val job = scope.launch(start = CoroutineStart.LAZY) { keepAlive() }

    suspend fun joinStart() {
        startup.await()
        job.start()
    }

    fun stop() {
        scope.cancel()
    }

    fun refreshIfExpired() {
        permanentFailure.value?.let { throw it }
        try {
            credentials.refreshIfExpired()
        } catch (e: IOException) {
            throw e.recordIfPermanent()
        }
    }

    private fun refreshOnce(): RefreshResult = try {
        credentials.refresh()
        val accessToken = requireNotNull(credentials.accessToken) {
            "Google OAuth refresh completed without an access token"
        }
        logger.info(
            "Google OAuth token was refreshed for {}, expires in at most {}s",
            credentials.projectId,
            accessToken.expiresInSeconds
        )
        RefreshResult.Success(accessToken)
    } catch (e: IOException) {
        if (e.isPermanentFailure()) {
            logger.error("OAuth refresh failed permanently for {}: {}", credentials.projectId, e.message)
            RefreshResult.PermanentFailure(e)
        } else {
            logger.warn("No new Google OAuth token for ${credentials.projectId} is available", e)
            RefreshResult.RetryableFailure(e)
        }
    }

    private suspend fun keepAlive() {
        while (currentCoroutineContext().isActive) {
            val recovering = permanentFailure.value != null
            delay(nextRefreshInterval(recovering))
            handleKeepAliveResult(refreshOnce(), recovering)
        }
        logger.info("OAuth keep-alive stopped: {} manager has been shut down", credentials.projectId)
    }

    private fun nextRefreshInterval(recovering: Boolean): Long {
        val interval = if (recovering) {
            recoveryBackOff.nextBackOffMillis()
        } else {
            backOff.nextBackOffMillis()
        }
        if (interval < 0L) {
            IllegalStateException(
                "Failed to refresh ${credentials.projectId} Google OAuth access token and back-off has already stopped"
            ).let {
                logger.error(it.message)
                throw it
            }
        }
        if (recovering) {
            logger.warn(
                "Scheduling OAuth recovery probe for {}, delayed {}s",
                credentials.projectId,
                TimeUnit.MILLISECONDS.toSeconds(interval)
            )
        } else {
            logger.info(
                "Scheduling task to refresh {} access token, delayed {}s",
                credentials.projectId,
                TimeUnit.MILLISECONDS.toSeconds(interval)
            )
        }
        return interval
    }

    private fun handleKeepAliveResult(result: RefreshResult, recovering: Boolean) {
        when (result) {
            is RefreshResult.Success -> {
                val recovered = permanentFailure.getAndSet(null) != null
                logger.info(
                    "Keep-alive OAuth refresh succeeded for {}, token expires in at most {}s",
                    credentials.projectId,
                    result.accessToken.expiresInSeconds
                )
                if (recovered) {
                    logger.info("OAuth credentials recovered for {}", credentials.projectId)
                }
                recoveryBackOff.reset()
                backOff.reset()
            }
            is RefreshResult.RetryableFailure -> {
                val message = if (recovering) {
                    "OAuth recovery probe failed for {}, will retry with recovery backoff: {}"
                } else {
                    "Keep-alive OAuth refresh failed for {}, will retry with backoff: {}"
                }
                logger.warn(message, credentials.projectId, result.exception.message)
            }
            is RefreshResult.PermanentFailure -> {
                recordPermanentFailure(result.exception)
                logger.error(
                    "Keep-alive OAuth refresh failed permanently for {}; " +
                        "normal refresh is suspended until a recovery probe succeeds: {}",
                    credentials.projectId,
                    result.exception.message
                )
            }
        }
    }

    private sealed interface RefreshResult {
        data class Success(val accessToken: AccessToken) : RefreshResult

        data class RetryableFailure(val exception: IOException) : RefreshResult

        data class PermanentFailure(val exception: IOException) : RefreshResult
    }

    private fun IOException.isPermanentFailure(): Boolean = this is Retryable && !isRetryable

    private fun IOException.recordIfPermanent() = if (isPermanentFailure()) {
        recordPermanentFailure(this)
    } else {
        this
    }

    private fun recordPermanentFailure(exception: IOException): IOException {
        permanentFailure.value = exception
        return exception
    }

    private companion object {
        val initialDelay: Duration = Duration.ofSeconds(2L)
    }
}
