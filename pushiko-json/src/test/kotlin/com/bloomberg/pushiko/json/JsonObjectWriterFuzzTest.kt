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

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
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

    @FuzzTest
    fun fuzzOperationSequence(data: FuzzedDataProvider) {
        val expected = JsonObject()
        val json = Buffer().use { buffer ->
            JsonObjectWriter(buffer).use {
                repeat(data.consumeInt(0, MAX_OPERATIONS)) {
                    val key = data.consumeString(MAX_STRING_LENGTH)
                    when (data.consumeInt(0, OPERATION_KINDS - 1)) {
                        0 -> data.consumeBoolean().also { value ->
                            booleanValue(key, value)
                            expected.add(key, JsonPrimitive(value))
                        }
                        1 -> data.consumeInt().also { value ->
                            intValue(key, value)
                            expected.add(key, JsonPrimitive(value))
                        }
                        2 -> data.consumeLong().also { value ->
                            longValue(key, value)
                            expected.add(key, JsonPrimitive(value))
                        }
                        3 -> data.consumeLong().toDouble().also { value ->
                            doubleValue(key, value)
                            expected.add(key, JsonPrimitive(value))
                        }
                        4 -> data.consumeString(MAX_STRING_LENGTH).also { value ->
                            stringValue(key, value)
                            expected.add(key, JsonPrimitive(value))
                        }
                        5 -> {
                            serializeNulls = data.consumeBoolean()
                            nullValue(key)
                            if (serializeNulls) {
                                expected.add(key, JsonNull.INSTANCE)
                            }
                        }
                        6 -> data.consumeInt().toShort().also { value ->
                            numericalValue(key, value)
                            expected.add(key, JsonPrimitive(value))
                        }
                        else -> data.consumeString(MAX_STRING_LENGTH).also { value ->
                            objectValue(key) {
                                stringValue(NESTED_KEY, value)
                            }
                            expected.add(key, JsonObject().apply {
                                add(NESTED_KEY, JsonPrimitive(value))
                            })
                        }
                    }
                }
            }
            buffer.readUtf8()
        }

        assertEquals(expected, JsonParser.parseString(json).asJsonObject)
    }

    companion object {
        private const val MAX_INPUT_SIZE = 4_096
        private const val MAX_OPERATIONS = 32
        private const val MAX_STRING_LENGTH = 128
        private const val OPERATION_KINDS = 8
        private const val NESTED_KEY = "value"

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
