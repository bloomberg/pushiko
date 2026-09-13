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

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes

internal class ApnsProviderTokenTest {
    private val keyPair = ecKeyPair()
    private val signingKey = ApnsSigningKey(KEY_ID, TEAM_ID, keyPair.private as ECPrivateKey)
    private val clock = Clock.fixed(ISSUED_AT, ZoneOffset.UTC)

    @Test
    fun createsValidProviderToken() {
        val authorization = ApnsProviderToken(signingKey, clock).currentAuthorization()
        val segments = authorization.removePrefix("bearer ").split('.')

        assertEquals(3, segments.size)
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(decoder.decode(segments[0]).toString(StandardCharsets.US_ASCII)).jsonObject
        val claims = Json.parseToJsonElement(decoder.decode(segments[1]).toString(StandardCharsets.US_ASCII)).jsonObject
        assertEquals("ES256", header.getValue("alg").jsonPrimitive.content)
        assertEquals("JWT", header.getValue("typ").jsonPrimitive.content)
        assertEquals(KEY_ID, header.getValue("kid").jsonPrimitive.content)
        assertEquals(TEAM_ID, claims.getValue("iss").jsonPrimitive.content)
        assertEquals(ISSUED_AT.epochSecond, claims.getValue("iat").jsonPrimitive.content.toLong())

        val signature = decoder.decode(segments[2])
        assertEquals(64, signature.size)
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(keyPair.public)
            update("${segments[0]}.${segments[1]}".toByteArray(StandardCharsets.US_ASCII))
            verify(joseToDer(signature))
        })
    }

    @Test
    fun generatedSignaturesRemainValidAcrossEcdsaIntegerEncodings() {
        val providerToken = ApnsProviderToken(signingKey, clock)
        repeat(128) {
            val authorization = providerToken.currentAuthorization()
            val segments = authorization.removePrefix("bearer ").split('.')
            val signature = Base64.getUrlDecoder().decode(segments[2])
            assertEquals(64, signature.size)
            assertTrue(Signature.getInstance("SHA256withECDSA").run {
                initVerify(keyPair.public)
                update("${segments[0]}.${segments[1]}".toByteArray(StandardCharsets.US_ASCII))
                verify(joseToDer(signature))
            })
            providerToken.invalidate(authorization)
        }
    }

    @Test
    fun cachesTokenForFiftyMinutes() {
        var nowNanos = 0L
        val providerToken = ApnsProviderToken(signingKey, clock, { nowNanos })
        val first = providerToken.currentAuthorization()

        nowNanos = 49L.minutes.inWholeNanoseconds
        assertSame(first, providerToken.currentAuthorization())

        nowNanos = 50L.minutes.inWholeNanoseconds
        assertNotEquals(first, providerToken.currentAuthorization())
    }

    @Test
    fun concurrentReadsShareToken() {
        val providerToken = ApnsProviderToken(signingKey, clock)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tokens = executor.invokeAll(List(64) { Callable { providerToken.currentAuthorization() } })
                .map { it.get() }
            tokens.forEach { assertSame(tokens.first(), it) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun invalidatesOnlyTokenUsedByResponse() {
        var nowNanos = 0L
        val providerToken = ApnsProviderToken(signingKey, clock, { nowNanos })
        val first = providerToken.currentAuthorization()
        nowNanos = 50L.minutes.inWholeNanoseconds
        val second = providerToken.currentAuthorization()

        providerToken.invalidate(first)
        assertSame(second, providerToken.currentAuthorization())

        providerToken.invalidate(second)
        assertNotEquals(second, providerToken.currentAuthorization())
    }

    private fun joseToDer(signature: ByteArray): ByteArray {
        require(signature.size == 64)
        val r = signature.copyOfRange(0, 32).derInteger()
        val s = signature.copyOfRange(32, 64).derInteger()
        return byteArrayOf(0x30, (2 + r.size + 2 + s.size).toByte(), 0x02, r.size.toByte()) +
            r + byteArrayOf(0x02, s.size.toByte()) + s
    }

    private fun ByteArray.derInteger(): ByteArray {
        val stripped = dropWhile { it == 0.toByte() }.toByteArray()
        val unsigned = if (stripped.isEmpty()) {
            byteArrayOf(0)
        } else {
            stripped
        }
        return if (unsigned[0].toInt() and 0x80 != 0) {
            byteArrayOf(0) + unsigned
        } else {
            unsigned
        }
    }

    private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private companion object {
        const val TEAM_ID = "TEAMID1234"
        const val KEY_ID = "KEYID12345"
        val ISSUED_AT: Instant = Instant.parse("2026-09-13T12:34:56Z")
    }
}
