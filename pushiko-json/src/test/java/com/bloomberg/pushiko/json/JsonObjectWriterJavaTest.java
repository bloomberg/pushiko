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

package com.bloomberg.pushiko.json;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonObjectWriterJavaTest {
    @Test
    void writeChainedValues() {
        try (Buffer buffer = new Buffer()) {
            final JsonObjectWriter writer = new JsonObjectWriter(buffer);
            try {
                writer.setSerializeNulls(true);
                writer.booleanValue("boolean", true)
                    .nullValue("null")
                    .intValue("int", 42)
                    .longValue("long", 43)
                    .doubleValue("double", 44.0)
                    .stringValue("string", "abc")
                    .literalValue("literalString", "def")
                    .literalValue("literalBytes", new byte[]{ '0' })
                    .objectValue("obj", w ->
                        w.stringValue("foo", "bar")
                    );
            } finally {
                writer.close();
            }
            final JsonObject obj = JsonParser.parseString(new String(buffer.readByteArray(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            assertEquals(JsonNull.INSTANCE, obj.get("null").getAsJsonNull());
            assertTrue(obj.get("boolean").getAsBoolean());
            assertEquals(42, obj.get("int").getAsInt());
            assertEquals(43, obj.get("long").getAsLong());
            assertEquals(44.0, obj.get("double").getAsDouble());
            assertEquals("abc", obj.get("string").getAsString());
            assertEquals("def", obj.get("literalString").getAsString());
            assertEquals("0", obj.get("literalBytes").getAsString());
            final JsonObject data = obj.getAsJsonObject("obj");
            assertEquals(1, data.keySet().size());
            assertEquals("bar", data.get("foo").getAsString());
        }
    }
}
