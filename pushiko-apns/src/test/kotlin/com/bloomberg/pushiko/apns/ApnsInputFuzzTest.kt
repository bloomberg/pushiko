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

package com.bloomberg.pushiko.apns

import com.bloomberg.pushiko.apns.keys.ApnsSigningKey
import com.code_intelligence.jazzer.junit.FuzzTest
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Path
import java.security.InvalidKeyException
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.stream.Stream
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.provider.MethodSource

internal class ApnsInputFuzzTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @MethodSource("inputs")
    @FuzzTest
    fun fuzzRequestAndSigningKey(input: ByteArray) {
        if (input.size > MAXIMUM_INPUT_SIZE) {
            return
        }

        fuzzRequest(input)
        fuzzSigningKey(input)
    }

    private fun fuzzRequest(input: ByteArray) {
        val firstSplit = input.size / 3
        val secondSplit = input.size * 2 / 3
        val deviceToken = input.copyOfRange(0, firstSplit).toString(Charsets.UTF_8)
        val topic = input.copyOfRange(firstSplit, secondSplit).toString(Charsets.UTF_8)
        val collapseId = input.copyOfRange(secondSplit, input.size).toString(Charsets.UTF_8)
        val result = runCatching {
            ApnsRequest {
                deviceToken(deviceToken)
                topic(topic)
                collapseId(collapseId)
                payload {
                    stringValue(PAYLOAD_KEY, input.toString(Charsets.UTF_8))
                }
            }
        }
        val shouldSucceed = deviceToken.isNotEmpty() &&
            sequenceOf(deviceToken, topic, collapseId).all { value ->
                value.none { it.isWhitespace() || it.isISOControl() }
            }
        assertEquals(shouldSucceed, result.isSuccess)
        result.getOrNull()?.let { request ->
            assertEquals(deviceToken, request.deviceToken)
            assertEquals(topic, request.headers.topic)
            assertEquals(collapseId, request.headers.collapseId)
            assertEquals(
                input.toString(Charsets.UTF_8),
                JsonParser.parseString(request.payloadString()).asJsonObject.get(PAYLOAD_KEY).asString
            )
        }
    }

    private fun fuzzSigningKey(input: ByteArray) {
        val file = temporaryDirectory.resolve("signing-key.p8").toFile().apply {
            writeBytes(input)
        }
        try {
            ApnsSigningKey.loadFromPkcs8File(file, KEY_ID, TEAM_ID).let {
                assertEquals(KEY_ID, it.keyId)
                assertEquals(TEAM_ID, it.teamId)
            }
        } catch (_: IOException) {
            // Malformed, oversized, and non-PEM inputs must fail in a controlled way.
        } catch (_: InvalidKeyException) {
            // Syntactically valid PEM can still contain an invalid or unsupported key.
        }
    }

    companion object {
        private const val MAXIMUM_INPUT_SIZE = 16 * 1_024 + 1
        private const val KEY_ID = "KEYID12345"
        private const val TEAM_ID = "TEAMID1234"
        private const val PAYLOAD_KEY = "input"

        @JvmStatic
        fun inputs(): Stream<ByteArray> = Stream.of(
            byteArrayOf(),
            "device-topic-collapse".toByteArray(),
            "device\r\ntopic".toByteArray(),
            validSigningKeyPem(),
            ByteArray(MAXIMUM_INPUT_SIZE)
        )

        private fun validSigningKeyPem(): ByteArray {
            val privateKey = KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair().private.encoded
            }
            val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(privateKey)
            return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n".toByteArray()
        }
    }
}
