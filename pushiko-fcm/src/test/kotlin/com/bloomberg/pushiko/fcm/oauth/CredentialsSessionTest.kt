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
import com.google.auth.oauth2.ServiceAccountCredentials
import kotlinx.coroutines.test.StandardTestDispatcher
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

internal class CredentialsSessionTest {
    private val dispatcher = StandardTestDispatcher()

    private val credentials = mock<ServiceAccountCredentials>().apply {
        whenever(projectId) doReturn "com.bloomberg.foo"
        whenever(accessToken) doReturn AccessToken("xyz", Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1L)))
    }
    private val session = CredentialsSession(credentials, dispatcher)

    @Test
    fun credentialsListener() {
        verify(credentials, times(1)).addChangeListener(any())
    }

    @Test
    fun projectId() {
        assertEquals("com.bloomberg.foo", session.projectId)
        verify(credentials, times(1)).projectId
    }

    @Test
    fun currentAuthorization() {
        repeat(2) {
            assertEquals("Bearer xyz", session.currentAuthorization)
        }
        verify(credentials, atLeastOnce()).accessToken
    }
}
