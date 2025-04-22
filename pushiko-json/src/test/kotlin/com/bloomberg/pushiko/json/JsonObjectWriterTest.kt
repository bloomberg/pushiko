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

package com.bloomberg.pushiko.json

import com.google.gson.JsonParser
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class JsonObjectWriterTest {
    @Test
    fun writeBooleanValue() {
        Buffer().use {
            JsonObjectWriter(it).use {
                booleanValue("key", true)
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertTrue(get("key").asBoolean)
        }
    }

    @Test
    fun writeIntValue() {
        Buffer().use {
            JsonObjectWriter(it).use {
                intValue("key", 42)
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertEquals(42, get("key").asInt)
        }
    }

    @Test
    fun writeLongValue() {
        Buffer().use {
            JsonObjectWriter(it).use {
                longValue("key", 42L)
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertEquals(42L, get("key").asLong)
        }
    }

    @Test
    fun writeNumericalValue() {
        Buffer().use {
            JsonObjectWriter(it).use {
                numericalValue("key", 42.toShort())
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertEquals(42, get("key").asShort)
        }
    }

    @Test
    fun writeStringValue() {
        Buffer().use {
            JsonObjectWriter(it).use {
                stringValue("key", "abc")
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertEquals("abc", get("key").asString)
        }
    }

    @Test
    fun writeObjectValue() {
        Buffer().use {
            JsonObjectWriter(it).use {
                objectValue("key") {}
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertEquals(0, get("key").asJsonObject.size())
        }
    }

    @Test
    fun writeJsonLiteralValueString() {
        Buffer().use {
            JsonObjectWriter(it).use {
                literalValue("key", "{\"foo\":\"bar\"}")
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertEquals("bar", get("key").asJsonObject
                .get("foo")
                .asString)
        }
    }

    @Test
    fun writeJsonLiteralValueByteArray() {
        Buffer().use {
            JsonObjectWriter(it).use {
                literalValue("key", "{\"foo\":\"bar\"}".encodeToByteArray())
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertEquals("bar", get("key").asJsonObject
                .get("foo")
                .asString)
        }
    }

    @Test
    fun writeJsonLiteralValueStringThenKeyValue() {
        Buffer().use {
            JsonObjectWriter(it).use {
                literalValue("key", "{\"foo\":\"bar\"}")
                stringValue("x", "y")
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertEquals("bar", get("key").asJsonObject.get("foo").asString)
            assertEquals("y", get("x").asString)
        }
    }

    @Test
    fun usePropagatesException() {
        Buffer().use {
            assertThrows<IllegalStateException> {
                JsonObjectWriter(it).use {
                    error("Uh-oh!")
                }
            }
        }
    }

    @Test
    fun writeNullTrue() {
        Buffer().use {
            JsonObjectWriter(it).use {
                serializeNulls = true
                assertTrue(serializeNulls)
                nullValue("foo")
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertTrue(has("foo"))
            assertTrue(this["foo"].isJsonNull)
        }
    }

    @Test
    fun writeNullFalse() {
        Buffer().use {
            JsonObjectWriter(it).use {
                serializeNulls = false
                assertFalse(serializeNulls)
                nullValue("foo")
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertFalse(has("foo"))
        }
    }

    @Test
    fun writeNullDefaultFalse() {
        Buffer().use {
            JsonObjectWriter(it).use {
                assertFalse(serializeNulls)
                nullValue("foo")
            }
            JsonParser.parseString(it.readUtf8()).asJsonObject
        }.run {
            assertFalse(has("foo"))
        }
    }
}
