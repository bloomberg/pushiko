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

package com.bloomberg.pushiko.apns.certificates

import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

internal class X509CertificateExtensionsTest {
    @Test
    fun hasExpired() {
        mock<X509Certificate> {
            whenever(it.notAfter) doReturn Date(0L)
        }.run {
            assertTrue(willExpireWithin(1L.milliseconds))
        }
    }

    @Test
    fun hasNotExpired() {
        mock<X509Certificate> {
            whenever(it.notAfter) doReturn Date(Instant.now().plus(1L.days.toJavaDuration()).toEpochMilli())
        }.run {
            assertFalse(willExpireWithin(1L.milliseconds))
        }
    }

    @Test
    fun willExpireIn7Days() {
        mock<X509Certificate> {
            whenever(it.notAfter) doReturn Date(Instant.now().plus(7L.days.minus(1L.milliseconds).toJavaDuration())
                .toEpochMilli())
        }.run {
            assertTrue(willExpireWithin(7L.days))
        }
    }
}
