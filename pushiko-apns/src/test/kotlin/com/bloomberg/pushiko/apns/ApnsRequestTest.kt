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

package com.bloomberg.pushiko.apns

import com.bloomberg.pushiko.apns.model.InterruptionLevel
import com.bloomberg.pushiko.apns.model.Priority
import com.bloomberg.pushiko.apns.model.PushType
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.times
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class ApnsRequestTest {
    @Test
    fun apsPropagatesException() {
        assertThrows<IllegalStateException> {
            ApnsRequest {
                deviceToken("abc123")
                topic("com.foo")
                aps {
                    error("Uh-oh!")
                }
            }
        }
    }

    @Test
    fun payloadPropagatesException() {
        assertThrows<IllegalStateException> {
            ApnsRequest {
                deviceToken("abc123")
                topic("com.foo")
                payload {
                    error("Uh-oh!")
                }
            }
        }
    }

    @Test
    fun minimal() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
        }.run {
            assertEquals("com.foo", headers.topic)
            assertEquals("abc123", deviceToken)
        }
    }

    @Test
    fun topicRequired() {
        assertThrows<UninitializedPropertyAccessException> {
            ApnsRequest {
                deviceToken("abc123")
            }
        }
    }

    @Test
    fun deviceTokenRequired() {
        assertThrows<UninitializedPropertyAccessException> {
            ApnsRequest {
                topic("com.foo")
            }
        }
    }

    @Test
    fun nonEmptyDeviceTokenRequired() {
        assertThrows<IllegalArgumentException> {
            ApnsRequest {
                deviceToken("")
                topic("com.foo")
            }
        }
    }

    @Test
    fun apnsRequestFull() {
        val uuid = UUID.randomUUID()
        val expiration = Instant.now()
        ApnsRequest {
            collapseId("collapse-me")
            expiration(expiration)
            id(uuid)
            priority(Priority.IMMEDIATE)
            pushType(PushType.ALERT)
            topic("com.foo")
            deviceToken("abc123")
            aps {}
            payload {
                stringValue("foo", "bar")
            }
        }.run {
            assertEquals("collapse-me", headers.collapseId)
            assertEquals(expiration, headers.expiration)
            assertEquals(uuid, headers.id)
            assertSame(Priority.IMMEDIATE, headers.priority)
            assertSame(PushType.ALERT, headers.pushType)
            assertEquals("com.foo", headers.topic)
            assertEquals("abc123", deviceToken)
            JsonParser.parseString(payloadString()).asJsonObject.run {
                assertTrue(has("aps"))
                assertEquals("bar", get("foo").asString)
            }
        }
    }

    @Test
    fun apnsRequestImplicitNulls() {
        ApnsRequest {
            topic("com.foo")
            deviceToken("abc123")
        }.run {
            assertNull(headers.collapseId)
            assertNull(headers.expiration)
            assertNull(headers.id)
            assertNull(headers.priority)
            assertNull(headers.pushType)
            assertEquals("com.foo", headers.topic)
            assertEquals("abc123", deviceToken)
            JsonParser.parseString(payloadString()).asJsonObject.run {
                assertFalse(has("aps"))
            }
        }
    }

    @Test
    fun alert() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                alert {
                    title("Hello")
                    body("World")
                    subtitle("test")
                }
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                val alert = getAsJsonObject("alert")
                assertEquals("Hello", alert["title"].asString)
                assertEquals("World", alert["body"].asString)
                assertEquals("test", alert["subtitle"].asString)
            }
        }
    }

    @Test
    fun badge() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                badge(42)
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals(42, get("badge").asInt)
            }
        }
    }

    @Test
    fun category() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                category("game")
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals("game", get("category").asString)
            }
        }
    }

    @Test
    fun contentAvailableTrue() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                isContentAvailable(true)
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals(1, get("content-available").asInt)
            }
        }
    }

    @Test
    fun contentAvailableFalse() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                isContentAvailable(false)
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals(0, get("content-available").asInt)
            }
        }
    }

    @Test
    fun mutableContentTrue() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                isMutableContent(true)
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals(1, get("mutable-content").asInt)
            }
        }
    }

    @Test
    fun mutableContentFalse() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                isMutableContent(false)
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals(0, get("mutable-content").asInt)
            }
        }
    }

    @Test
    fun interruptionLevelActive() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                interruptionLevel(InterruptionLevel.ACTIVE)
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals("active", get("interruption-level").asString)
            }
        }
    }

    @Test
    fun interruptionLevelCritical() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                interruptionLevel(InterruptionLevel.CRITICAL)
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals("critical", get("interruption-level").asString)
            }
        }
    }

    @Test
    fun interruptionLevelPassive() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                interruptionLevel(InterruptionLevel.PASSIVE)
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals("passive", get("interruption-level").asString)
            }
        }
    }

    @Test
    fun interruptionLevelTimeSensitive() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                interruptionLevel(InterruptionLevel.TIME_SENSITIVE)
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals("time-sensitive", get("interruption-level").asString)
            }
        }
    }

    @Test
    fun relevanceScore() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                relevanceScore(1.0)
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals(1.0, get("relevance-score").asDouble)
            }
        }
    }

    @Test
    fun sound() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                sound("tada")
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals("tada", get("sound").asString)
            }
        }
    }

    @Test
    fun targetContentId() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                targetContentId("xyz")
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals("xyz", get("target-content-id").asString)
            }
        }
    }

    @Test
    fun threadId() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                threadId("xyz")
            }
        }.run {
            JsonParser.parseString(payloadString()).asJsonObject.getAsJsonObject("aps").run {
                assertEquals("xyz", get("thread-id").asString)
            }
        }
    }

    @Test
    fun bodyToJson() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                alert {
                    title("Hello")
                }
            }
            payload { longValue("a", 42L) }
        }.run {
            val body = JsonParser.parseString(payloadString()).asJsonObject
            assertNull(body["apns-id"])
            assertNull(body["apns-priority"])
            assertNull(body["apns-topic"])
            assertEquals(body["a"].asLong, 42L)
            assertEquals("Hello", body["aps"].asJsonObject["alert"].asJsonObject["title"].asString)
        }
    }

    @Test
    fun emptyPayloadToJson() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {}
            payload {}
        }.run {
            val body = JsonParser.parseString(payloadString()).asJsonObject
            assertTrue(body.has("aps"))
            assertEquals(body.keySet().size, 1)
            assertTrue(body["aps"].asJsonObject.keySet().isEmpty())
        }
    }

    @Test
    fun nullPayloadToJson() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {}
        }.run {
            val body = JsonParser.parseString(payloadString()).asJsonObject
            assertTrue(body.has("aps"))
            assertEquals(1, body.keySet().size)
        }
    }

    @Test
    fun apsViaPayload() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            payload {
                objectValue("aps") {
                    objectValue("alert") {
                        stringValue("title", "Hello")
                    }
                }
            }
        }.run {
            val body = JsonParser.parseString(payloadString()).asJsonObject
            assertEquals("Hello", body["aps"].asJsonObject["alert"].asJsonObject["title"].asString)
        }
    }

    @Test
    fun apsLiveActivity() {
        ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            pushType(PushType.LIVE_ACTIVITY)
            aps {
                event ("update")
                timestamp(Instant.now().epochSecond)
            }
        }.run {
            val body = JsonParser.parseString(payloadString()).asJsonObject
            val aps = body["aps"].asJsonObject
            assertEquals("update", aps["event"].asString)
            assertTrue(aps["timestamp"].asLong >= Instant.now().epochSecond)
        }
    }
}
