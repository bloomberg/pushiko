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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
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

    private fun ApnsClient.Builder.configurePrivateKey() {
        clientCredentials(
            File(javaClass.classLoader.getResource("keystore.pkcs12")!!.toURI()),
            "changeit".toCharArray()
        )
    }
}
