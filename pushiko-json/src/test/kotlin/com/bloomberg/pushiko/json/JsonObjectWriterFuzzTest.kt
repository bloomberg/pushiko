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

package com.bloomberg.pushiko.json

import com.code_intelligence.jazzer.junit.FuzzTest
import com.google.gson.JsonParser
import java.util.stream.Stream
import kotlin.test.assertEquals
import okio.Buffer
import org.junit.jupiter.params.provider.MethodSource

internal class JsonObjectWriterFuzzTest {
    @MethodSource("inputs")
    @FuzzTest
    fun fuzzStringValue(input: ByteArray) {
        if (input.size > MAX_INPUT_SIZE) {
            return
        }

        val split = input.size / 2
        val key = input.copyOfRange(0, split).toString(Charsets.UTF_8)
        val value = input.copyOfRange(split, input.size).toString(Charsets.UTF_8)
        val json = Buffer().use { buffer ->
            JsonObjectWriter(buffer).use {
                stringValue(key, value)
            }
            buffer.readUtf8()
        }
        val parsed = JsonParser.parseString(json).asJsonObject
        assertEquals(1, parsed.size())
        assertEquals(value, parsed.get(key).asString)
    }

    companion object {
        private const val MAX_INPUT_SIZE = 4_096

        @JvmStatic
        fun inputs(): Stream<ByteArray> = Stream.of(
            byteArrayOf(),
            "keyvalue".toByteArray(),
            "\"\\\n\u0000\"\\\r\t".toByteArray(),
            "£€😀\u2028£€😀\u2029".toByteArray(),
            byteArrayOf(0xC0.toByte(), 0x80.toByte())
        )
    }
}
