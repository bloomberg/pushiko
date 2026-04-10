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
import com.google.auth.oauth2.OAuth2Credentials
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.annotation.concurrent.ThreadSafe

private val oauthFailedException = IllegalStateException(
    "Failed to refresh Google OAuth access token and back-off has already stopped")

@ThreadSafe
internal class CredentialsRefreshManager(
    private val credentials: OAuth2Credentials,
    dispatcher: CoroutineDispatcher,
    private val backOff: BackOff = OAuthRefreshBackOff(credentials)
) {
    private val logger = Logger()
    private val iterator = iterator {
        while (true) {
            try {
                credentials.run {
                    refresh()
                    accessToken.expiresInSeconds.let {
                        logger.info("Google OAuth token was refreshed, expires in at most {}s", it)
                    }
                    yield(accessToken)
                }
            } catch (e: IOException) {
                logger.warn("No new Google OAuth token is available", e)
                yield(null)
            }
        }
    }
    private val scope = CoroutineScope(dispatcher)
    private val job = scope.launch(start = CoroutineStart.LAZY) { keepAlive() }

    init {
        while (iterator.next() == null) {
            Thread.sleep(initialDelay.toMillis())
        }
        job.apply {
            start()
            check(isActive) { "OAuth session is not being kept alive" }
        }
    }

    fun stop() {
        job.cancel()
    }

    private tailrec suspend fun keepAlive() {
        val interval = backOff.nextBackOffMillis()
        if (interval < 0L) {
            logger.error(oauthFailedException.message)
            throw oauthFailedException
        }
        TimeUnit.MILLISECONDS.toSeconds(interval).let {
            logger.info("Scheduling task to refresh access token, delayed {}s", it)
        }
        delay(interval)
        if (iterator.next() != null) {
            backOff.reset()
        }
        keepAlive()
    }

    private companion object {
        val initialDelay: Duration = Duration.ofSeconds(2L)
    }
}
