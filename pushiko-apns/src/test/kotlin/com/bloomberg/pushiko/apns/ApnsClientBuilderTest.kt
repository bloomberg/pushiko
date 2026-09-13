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

package com.bloomberg.pushiko.apns

import com.bloomberg.pushiko.apns.keys.ApnsSigningKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class ApnsClientBuilderTest {
    @Test
    fun environmentDefault() {
        ApnsClient {
            configurePrivateKey()
            assertSame(ApnsEnvironment.PRODUCTION, environment)
        }.run {
            runTest {
                close()
            }
        }
    }

    @Test
    fun environmentDevelopment() {
        ApnsClient {
            configurePrivateKey()
            environment = ApnsEnvironment.DEVELOPMENT
            assertSame(ApnsEnvironment.DEVELOPMENT, environment)
        }.run {
            runTest {
                close()
            }
        }
    }

    @Test
    fun environmentProduction() {
        ApnsClient {
            configurePrivateKey()
            environment = ApnsEnvironment.PRODUCTION
            assertSame(ApnsEnvironment.PRODUCTION, environment)
        }.run {
            runTest {
                close()
            }
        }
    }

    @Test
    fun maximumMinimumConnections() {
        ApnsClient {
            configurePrivateKey()
            maximumConnections = 42
            minimumConnections = 41
            assertEquals(42, maximumConnections)
            assertEquals(41, minimumConnections)
        }.run {
            runTest {
                close()
            }
        }
    }

    @Test
    fun excessiveMinimumConnections() {
        assertThrows<IllegalArgumentException> {
            ApnsClient {
                configurePrivateKey()
                minimumConnections = Int.MAX_VALUE
            }.run {
                runTest {
                    close()
                }
            }
        }
    }

    @Test
    fun proxyAddress() {
        ApnsClient {
            configurePrivateKey()
            proxy("proxy.foo.com", 80)
            assertTrue(proxyAddress!!.isUnresolved)
            assertEquals("proxy.foo.com", proxyAddress!!.hostName)
            assertEquals(80, proxyAddress!!.port)
        }
    }

    @Test
    fun requiresPrivateKey() {
        assertThrows<IllegalArgumentException> {
            ApnsClient { }
        }
    }

    @Test
    fun tokenAuthentication() {
        ApnsClient {
            signingKey(signingKey())
        }.run {
            runTest { close() }
        }
    }

    @Test
    fun rejectsCertificateThenTokenCredentials() {
        assertThrows<IllegalArgumentException> {
            ApnsClient.Builder().apply {
                configurePrivateKey()
                signingKey(signingKey())
            }
        }
    }

    @Test
    fun rejectsTokenThenCertificateCredentials() {
        assertThrows<IllegalArgumentException> {
            ApnsClient.Builder().apply {
                signingKey(signingKey())
                configurePrivateKey()
            }
        }
    }

    private fun ApnsClient.Builder.configurePrivateKey() {
        clientCredentials(
            File(javaClass.classLoader.getResource("keystore.pkcs12")!!.toURI()),
            "changeit".toCharArray()
        )
    }

    private fun signingKey(): ApnsSigningKey {
        val privateKey = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair().private as ECPrivateKey
        }
        return ApnsSigningKey("KEYID12345", "TEAMID1234", privateKey)
    }
}
