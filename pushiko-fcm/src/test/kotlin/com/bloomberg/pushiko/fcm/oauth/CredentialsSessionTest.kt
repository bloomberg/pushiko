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
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

internal class CredentialsSessionTest {
    private val dispatcher = StandardTestDispatcher()
    private val failOnDispatch = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            throw AssertionError("Fresh-token path must not dispatch")
        }
    }
    private val now = Instant.parse("2026-09-13T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val credentials = mock<ServiceAccountCredentials>().apply {
        whenever(projectId) doReturn "com.bloomberg.foo"
        whenever(accessToken) doReturn this@CredentialsSessionTest.accessToken("xyz")
    }
    private val session = CredentialsSession(credentials, dispatcher, clock)

    @Test
    fun projectId() {
        assertEquals("com.bloomberg.foo", session.projectId)
        verify(credentials, times(1)).projectId
    }

    @Test
    fun currentAuthorization() = runTest(dispatcher) {
        repeat(2) {
            assertEquals("Bearer xyz", session.currentAuthorization())
        }
        verify(credentials, atLeastOnce()).accessToken
        verify(credentials, never()).refreshIfExpired()
    }

    @Test
    fun freshTokenDoesNotSwitchDispatcher() = runTest {
        assertEquals(
            "Bearer xyz",
            CredentialsSession(credentials, failOnDispatch, clock).currentAuthorization()
        )
    }

    @Test
    fun currentAuthorizationUsesLatestToken() = runTest(dispatcher) {
        val credentials = mock<ServiceAccountCredentials>().apply {
            whenever(projectId) doReturn "com.bloomberg.foo"
            whenever(accessToken).thenReturn(
                this@CredentialsSessionTest.accessToken("first"),
                this@CredentialsSessionTest.accessToken("second")
            )
        }
        val session = CredentialsSession(credentials, dispatcher, clock)

        assertEquals("Bearer first", session.currentAuthorization())
        assertEquals("Bearer second", session.currentAuthorization())
    }

    @Test
    fun rejectsExpiredOrImminentlyExpiringToken() = runTest(dispatcher) {
        listOf(-1L, 0L, 180L).forEach { expiresInSeconds ->
            val credentials = mock<ServiceAccountCredentials>().apply {
                whenever(projectId) doReturn "com.bloomberg.foo"
                whenever(accessToken) doReturn this@CredentialsSessionTest.accessToken("stale", expiresInSeconds)
            }

            assertFailsWith<IllegalStateException> {
                CredentialsSession(credentials, dispatcher, clock).currentAuthorization()
            }
        }
    }

    @Test
    fun refreshesImminentlyExpiringTokenOffTheFastPath() = runTest(dispatcher) {
        val credentials = mock<ServiceAccountCredentials>().apply {
            whenever(projectId) doReturn "com.bloomberg.foo"
            whenever(accessToken).thenReturn(
                this@CredentialsSessionTest.accessToken("stale", 180L),
                this@CredentialsSessionTest.accessToken("fresh")
            )
        }

        assertEquals(
            "Bearer fresh",
            CredentialsSession(credentials, dispatcher, clock).currentAuthorization()
        )
        verify(credentials, times(1)).refreshIfExpired()
    }

    @Test
    fun rejectsTokenWithoutExpiration() = runTest(dispatcher) {
        val credentials = mock<ServiceAccountCredentials>().apply {
            whenever(projectId) doReturn "com.bloomberg.foo"
            whenever(accessToken) doReturn AccessToken("xyz", null)
        }

        assertFailsWith<IllegalArgumentException> {
            CredentialsSession(credentials, dispatcher, clock).currentAuthorization()
        }
    }

    @Test
    fun refreshFailureIsPropagated() = runTest(dispatcher) {
        val exception = IOException("refresh failed")
        val credentials = mock<ServiceAccountCredentials>().apply {
            whenever(projectId) doReturn "com.bloomberg.foo"
            whenever(refreshIfExpired()) doThrow exception
        }

        assertEquals(exception.message, assertFailsWith<IOException> {
            CredentialsSession(credentials, dispatcher, clock).currentAuthorization()
        }.message)
    }

    @Test
    fun constructorDoesNotRefreshCredentials() {
        val credentials = mock<ServiceAccountCredentials>().apply {
            whenever(projectId) doReturn "com.bloomberg.foo"
            whenever(accessToken) doReturn null
        }
        CredentialsSession(credentials, dispatcher)
        verify(credentials, never()).refreshIfExpired()
        verify(credentials, never()).refreshAccessToken()
    }

    private fun accessToken(value: String, expiresInSeconds: Long = 300L) = AccessToken(
        value,
        Date.from(now.plusSeconds(expiresInSeconds))
    )
}
