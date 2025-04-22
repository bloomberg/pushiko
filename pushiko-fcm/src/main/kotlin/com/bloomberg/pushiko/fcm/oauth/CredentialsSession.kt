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
import com.google.auth.oauth2.OAuth2Credentials
import com.google.auth.oauth2.ServiceAccountCredentials
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.Locale
import javax.annotation.concurrent.ThreadSafe

private fun String.bearer() = "Bearer $this"
private fun String.fcmSendPath() = "/v1/projects/%s/messages:send".format(Locale.US, this)

@ThreadSafe
internal class CredentialsSession(
    private val credentials: ServiceAccountCredentials,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) : Session {
    override val projectId: String = credentials.projectId

    override val sendPath = projectId.fcmSendPath()

    private val logger = Logger()
    private val credentialsChangedListener = OAuth2Credentials.CredentialsChangedListener {
        this@CredentialsSession.currentAuthorization = it.accessToken.tokenValue
        logger.info("Refreshed access token for: {}", projectId)
    }.also {
        credentials.addChangeListener(it)
    }
    private val credentialsRefresher = CredentialsRefreshManager(credentials, dispatcher)

    @Volatile
    override var currentAuthorization: String = credentials.run {
        (accessToken ?: refreshAccessToken()).tokenValue
    }.bearer()
        set(value) { field = value.bearer() }

    override fun close() {
        logger.info("Closing credential session, id: {}", projectId)
        credentialsRefresher.stop()
        credentials.removeChangeListener(credentialsChangedListener)
    }
}
