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

import com.google.auth.Retryable
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.ServiceAccountCredentials
import kotlinx.coroutines.Dispatchers
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.util.Date
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.doThrow

internal class CredentialsRefreshManagerTest {
    private val credentials = mock<ServiceAccountCredentials>()
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
    fun refreshSuccess() = runTest {
        val backOff = mock<BackOff>()
        val iterator = sequence {
            yield(0L)
            yield(DEFAULT_EXPIRY_MILLIS)
        }.iterator()
        whenever(backOff.nextBackOffMillis()).thenAnswer { iterator.next() }
        manager = CredentialsRefreshManager(credentials, Dispatchers.Unconfined, backOff)
        manager.joinStart()
        assertEquals(ANOTHER_FAKE_TOKEN, credentials.accessToken.tokenValue)
        verify(backOff, times(1)).reset()
    }

    @Test
    fun backOffCanExpire() {
        val backOff = mock<BackOff>()
        whenever(backOff.nextBackOffMillis()).thenReturn(-1L)
        manager = CredentialsRefreshManager(credentials, Dispatchers.Unconfined, backOff)
        assertFailsWith<IllegalStateException> {
            runTest {
                manager.joinStart()
            }
        }
        verify(backOff, times(1)).nextBackOffMillis()
    }

    @Test
    fun startRetriesWithInitiallyNullAccessToken() {
        val credentials = mock<ServiceAccountCredentials>()
        val iterator = sequence {
            yield(AccessToken(FAKE_TOKEN, Date(System.currentTimeMillis() + DEFAULT_EXPIRY_MILLIS)))
        }.iterator()
        whenever(credentials.refresh()).then {
            whenever(credentials.accessToken).thenReturn(iterator.next())
        }
        manager = CredentialsRefreshManager(credentials, coroutineDispatcher, backOff)
        runTest {
            manager.joinStart()
        }
        verify(credentials, times(1)).refresh()
    }

    @Test
    fun startFailsFastWhenRefreshFailureIsNonRetryable() = runTest {
        val credentials = mock<ServiceAccountCredentials>()
        val exception = NonRetryableIOException("invalid_grant")
        whenever(credentials.refresh()) doThrow exception
        manager = CredentialsRefreshManager(credentials, Dispatchers.Unconfined, backOff)
        assertFailsWith<IOException> {
            manager.joinStart()
        }.let {
            assertSame(exception, it)
        }
    }

    @Test
    fun startIsCancellable() {
        val credentials = mock<ServiceAccountCredentials>()
        val exception = RetryableIOException("retryable_error")
        whenever(credentials.refresh()) doThrow exception
        manager = CredentialsRefreshManager(credentials, Dispatchers.Unconfined, backOff)
        thread(start = true, isDaemon = true) {
            Thread.sleep(100L)
            manager.stop()
        }
        assertFailsWith<CancellationException> {
            runTest {
                manager.joinStart()
            }
        }
    }

    private companion object {
        private const val DEFAULT_EXPIRY_MILLIS = 10_000L * 1_000L
        private const val FAKE_TOKEN = "abc123"
        private const val ANOTHER_FAKE_TOKEN = "xyz456"
    }

    private class RetryableIOException(message: String) : IOException(message), Retryable {
        override fun isRetryable() = true

        override fun getRetryCount() = 0
    }

    private class NonRetryableIOException(message: String) : IOException(message), Retryable {
        override fun isRetryable() = false

        override fun getRetryCount() = 0
    }
}
