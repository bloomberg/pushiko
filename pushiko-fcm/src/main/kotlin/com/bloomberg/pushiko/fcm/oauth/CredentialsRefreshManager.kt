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
                    yield(accessToken)
                }
            } catch (e: IOException) {
                logger.warn("No new Google OAuth token for ${credentials.projectId} is available", e)
                yield(null)
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        while (iterator.next() == null) {
            Thread.sleep(initialDelay.toMillis())
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
            if (iterator.next() != null) {
                backOff.reset()
            }
        }
        logger.info("OAuth keep-alive stopped: {} manager has been shut down", credentials.projectId)
    }

    private companion object {
        val initialDelay: Duration = Duration.ofSeconds(2L)
    }
}
