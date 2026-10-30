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

package com.bloomberg.pushiko.fcm.oauth

import com.bloomberg.pushiko.fcm.FcmRequest
import com.code_intelligence.jazzer.junit.FuzzTest
import com.google.gson.JsonParser
import java.util.stream.Stream
import kotlin.test.assertEquals
import org.junit.jupiter.params.provider.MethodSource

internal class FcmInputFuzzTest {
    @MethodSource("inputs")
    @FuzzTest
    fun fuzzProjectIdAndRequest(input: ByteArray) {
        if (input.size > MAXIMUM_INPUT_SIZE) {
            return
        }

        val value = input.toString(Charsets.UTF_8)
        val projectId = runCatching { value.requireValidProjectId() }
        assertEquals(value.isValidProjectId(), projectId.isSuccess)
        projectId.getOrNull()?.let { assertEquals(value, it) }

        val validateOnly = input.firstOrNull()?.toInt()?.and(1) == 1
        val request = FcmRequest {
            validateOnly(validateOnly)
            message {
                token(value)
                data {
                    stringValue(value, value)
                }
            }
        }
        JsonParser.parseString(request.payloadString()).asJsonObject.let { json ->
            assertEquals(validateOnly, json.get("validate_only").asBoolean)
            json.getAsJsonObject("message").let { message ->
                assertEquals(value, message.get("token").asString)
                assertEquals(value, message.getAsJsonObject("data").get(value).asString)
            }
        }
    }

    private fun String.isValidProjectId(): Boolean = length in 6..30 &&
        first() in 'a'..'z' &&
        (last() in 'a'..'z' || last() in '0'..'9') &&
        drop(1).dropLast(1).all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    companion object {
        private const val MAXIMUM_INPUT_SIZE = 4_096

        @JvmStatic
        fun inputs(): Stream<ByteArray> = Stream.of(
            byteArrayOf(),
            "a1234".toByteArray(),
            "a12345".toByteArray(),
            "project-id".toByteArray(),
            ("a" + "1".repeat(29)).toByteArray(),
            ("a" + "1".repeat(30)).toByteArray(),
            "Project-ID".toByteArray(),
            "project\nid".toByteArray(),
            "prøject-id".toByteArray()
        )
    }
}
