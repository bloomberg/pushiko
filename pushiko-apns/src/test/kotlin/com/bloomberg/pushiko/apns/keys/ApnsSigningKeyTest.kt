/*
 * Copyright 2026 Bloomberg Finance L.P.
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

import java.io.File
import java.security.InvalidKeyException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class ApnsSigningKeyTest {
    @Test
    fun loadFromPkcs8File() = withPkcs8File(ecKeyPair().private.encoded) { file ->
        val key = ApnsSigningKey.loadFromPkcs8File(file, KEY_ID, TEAM_ID)

        assertEquals(TEAM_ID, key.teamId)
        assertEquals(KEY_ID, key.keyId)
    }

    @Test
    fun rejectsInvalidIdentifiers() {
        val privateKey = ecKeyPair().private as ECPrivateKey
        listOf("", "lowercase1", "TOO-SHORT", "ABCDEFGHIJK").forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) {
                ApnsSigningKey(invalid, TEAM_ID, privateKey)
            }
            assertFailsWith<IllegalArgumentException>(invalid) {
                ApnsSigningKey(KEY_ID, invalid, privateKey)
            }
        }
    }

    @Test
    fun rejectsNonP256Key() {
        val privateKey = ecKeyPair("secp384r1").private as ECPrivateKey

        assertFailsWith<InvalidKeyException> {
            ApnsSigningKey(KEY_ID, TEAM_ID, privateKey)
        }
    }

    @Test
    fun rejectsNonEcPkcs8Key() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        withPkcs8File(keyPair.private.encoded) { file ->
            assertFailsWith<InvalidKeyException> {
                ApnsSigningKey.loadFromPkcs8File(file, KEY_ID, TEAM_ID)
            }
        }
    }

    @Test
    fun rejectsMalformedPem() {
        val path = createTempFile(prefix = "apns-signing-key", suffix = ".p8")
        try {
            path.writeText("not a private key")
            assertFailsWith<java.io.IOException> {
                ApnsSigningKey.loadFromPkcs8File(path.toFile(), KEY_ID, TEAM_ID)
            }
        } finally {
            path.deleteIfExists()
        }
    }

    private fun withPkcs8File(encoded: ByteArray, block: (File) -> Unit) {
        val path = createTempFile(prefix = "apns-signing-key", suffix = ".p8")
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(encoded)
        try {
            path.writeText("-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n")
            block(path.toFile())
        } finally {
            path.deleteIfExists()
        }
    }

    private fun ecKeyPair(curve: String = "secp256r1"): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec(curve))
        generateKeyPair()
    }

    private companion object {
        const val TEAM_ID = "TEAMID1234"
        const val KEY_ID = "KEYID12345"
    }
}
