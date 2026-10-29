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

package com.bloomberg.pushiko.apns.keys

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.KeyStore.PasswordProtection
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

internal class Pkcs12Test {
    private val password = "changeit".toCharArray()

    @Test
    fun pkcs12PrivateKeyEntry() {
        val entry = keyStoreFile().inputStream().use {
            it.pkcs12PrivateKeyEntry(password)
        }
        assertEquals(2, entry.attributes.size)
        assertNotNull(entry.attributes.firstOrNull { it.value == "BLP" })
    }

    @Test
    fun rejectsPkcs12WithoutPrivateKeyEntry() {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
        }
        val exception = assertFailsWith<IllegalArgumentException> {
            keyStore.encoded().inputStream().pkcs12PrivateKeyEntry(password)
        }
        assertEquals("PKCS#12 must contain exactly one private key entry; found 0", exception.message)
    }

    @Test
    fun rejectsPkcs12WithOnlyTrustedCertificateEntry() {
        val sourceKeyStore = keyStoreFile().inputStream().use { input ->
            KeyStore.getInstance("PKCS12").apply {
                load(input, password)
            }
        }
        val certificate = sourceKeyStore.getCertificate(sourceKeyStore.aliases().nextElement())
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setCertificateEntry("trusted", certificate)
        }
        val exception = assertFailsWith<IllegalArgumentException> {
            keyStore.encoded().inputStream().pkcs12PrivateKeyEntry(password)
        }
        assertEquals("PKCS#12 must contain exactly one private key entry; found 0", exception.message)
    }

    @Test
    fun rejectsPkcs12WithMultiplePrivateKeyEntries() {
        val keyStore = keyStoreFile().inputStream().use { input ->
            KeyStore.getInstance("PKCS12").apply {
                load(input, password)
            }
        }
        val firstAlias = keyStore.aliases().nextElement()
        val protection = PasswordProtection(password)
        try {
            keyStore.setEntry("second", keyStore.getEntry(firstAlias, protection), protection)
        } finally {
            protection.destroy()
        }
        val exception = assertFailsWith<IllegalArgumentException> {
            keyStore.encoded().inputStream().pkcs12PrivateKeyEntry(password)
        }
        assertEquals("PKCS#12 must contain exactly one private key entry; found 2", exception.message)
    }

    private fun keyStoreFile() = File(javaClass.classLoader.getResource("keystore.pkcs12")!!.toURI())

    private fun KeyStore.encoded() = ByteArrayOutputStream().use { output ->
        store(output, password)
        output.toByteArray()
    }
}
