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
import com.google.auth.oauth2.OAuth2Credentials
import kotlinx.coroutines.Dispatchers
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class CredentialsRefreshManagerTest {
    private val credentials = mock<OAuth2Credentials>()
    private lateinit var manager: CredentialsRefreshManager
    private val backOff = mock<BackOff>()
    private val coroutineDispatcher = Dispatchers.Default

    @BeforeTest
    fun setUp() {
        val iterator = sequence {
            yield(AccessToken(FAKE_TOKEN, Date(System.currentTimeMillis() + DEFAULT_EXPIRY_MILLIS)))
            yield(AccessToken(ANOTHER_FAKE_TOKEN, Date(System.currentTimeMillis() + DEFAULT_EXPIRY_MILLIS)))
        }.iterator()
        whenever(backOff.nextBackOffMillis()).thenReturn(DEFAULT_EXPIRY_MILLIS)
        whenever(credentials.refresh()).then {
            whenever(credentials.accessToken).thenReturn(iterator.next())
            true
        }
    }

    @AfterTest
    fun tearDown() {
        if (::manager.isInitialized) {
            manager.stop()
        }
    }

    @Test
    fun refreshSuccess() {
        val backOff = mock<BackOff>()
        val iterator = sequence {
            yield(0L)
            yield(DEFAULT_EXPIRY_MILLIS)
        }.iterator()
        whenever(backOff.nextBackOffMillis()).thenAnswer { iterator.next() }
        manager = CredentialsRefreshManager(credentials, Dispatchers.Unconfined, backOff)
        assertEquals(ANOTHER_FAKE_TOKEN, credentials.accessToken.tokenValue)
        verify(backOff, times(1)).reset()
    }

    @Test
    fun backOffCanExpire() {
        val backOff = mock<BackOff>()
        whenever(backOff.nextBackOffMillis()).thenReturn(-1L)
        assertFailsWith<IllegalStateException> {
            manager = CredentialsRefreshManager(credentials, Dispatchers.Unconfined, backOff)
        }
    }

    @Test
    fun initRetriesWithInitiallyNullAccessToken() {
        val credentials = mock<OAuth2Credentials>()
        val iterator = sequence {
            yield(AccessToken(FAKE_TOKEN, Date(System.currentTimeMillis() + DEFAULT_EXPIRY_MILLIS)))
        }.iterator()
        whenever(credentials.refresh()).then {
            whenever(credentials.accessToken).thenReturn(iterator.next())
        }
        manager = CredentialsRefreshManager(credentials, coroutineDispatcher, backOff)
        verify(credentials, times(1)).refresh()
    }

    private companion object {
        private const val DEFAULT_EXPIRY_MILLIS = 10_000L * 1_000L
        private const val FAKE_TOKEN = "abc123"
        private const val ANOTHER_FAKE_TOKEN = "xyz456"
    }
}
