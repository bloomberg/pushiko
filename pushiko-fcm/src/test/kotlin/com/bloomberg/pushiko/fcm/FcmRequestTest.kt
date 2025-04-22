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

package com.bloomberg.pushiko.fcm

import com.bloomberg.pushiko.fcm.model.android.AndroidMessagePriority
import com.bloomberg.pushiko.fcm.model.android.NotificationPriority
import com.bloomberg.pushiko.fcm.model.android.Visibility
import com.google.gson.JsonParser
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

internal class FcmRequestTest {
    @Test
    fun notification() {
        JsonParser.parseString(FcmRequest {
            validateOnly(true)
            message {
                token("xyz")
                notification {
                    title("Hello")
                    body("World")
                    image("https://foo.com/icon")
                }
            }
        }.payloadString()).asJsonObject.apply {
            assertTrue(getAsJsonPrimitive("validate_only").asBoolean)
            val message = getAsJsonObject("message")
            assertEquals("xyz", message["token"].asString)
            val notification = message["notification"].asJsonObject
            assertEquals("Hello", notification["title"].asString)
            assertEquals("World", notification["body"].asString)
            assertEquals("https://foo.com/icon", notification["image"].asString)
        }
    }

    @Test
    fun androidNotification() {
        JsonParser.parseString(FcmRequest {
            validateOnly(true)
            message {
                token("xyz")
                data {
                    stringValue("identifier", "123")
                }
                android {
                    collapseKey("abc")
                    priority(AndroidMessagePriority.HIGH)
                    restrictedPackageName("com.bloomberg.foo")
                    timeToLiveSeconds(1L)
                    notification {
                        defaultSound(true)
                        image("https://foo.com/icon")
                        notificationPriority(NotificationPriority.HIGH)
                        sound()
                        visibility(Visibility.PRIVATE)
                    }
                }
            }
        }.payloadString()).asJsonObject.apply {
            assertTrue(getAsJsonPrimitive("validate_only").asBoolean)
            val message = getAsJsonObject("message")
            assertEquals("xyz", message["token"].asString)
            val data = message["data"].asJsonObject
            assertEquals(1, data.keySet().size)
            assertEquals("123", data["identifier"].asString)
            assertEquals("abc", message["android"].asJsonObject["collapse_key"].asString)
            assertEquals(AndroidMessagePriority.HIGH.value, message["android"].asJsonObject["priority"].asString)
            assertEquals("com.bloomberg.foo", message["android"].asJsonObject["restricted_package_name"].asString)
            assertEquals("1s", message["android"].asJsonObject["ttl"].asString)
            val notification = message["android"].asJsonObject["notification"].asJsonObject
            assertTrue(notification["default_sound"].asBoolean)
            assertEquals("https://foo.com/icon", notification["image"].asString)
            assertEquals("PRIORITY_HIGH", notification["notification_priority"].asString)
            assertEquals("default", notification["sound"].asString)
            assertEquals("PRIVATE", notification["visibility"].asString)
        }
    }

    @Test
    fun androidNotificationTimeToLive() {
        JsonParser.parseString(FcmRequest {
            validateOnly(true)
            message {
                token("xyz")
                android {
                    timeToLive("42s")
                }
            }
        }.payloadString()).asJsonObject.apply {
            val message = getAsJsonObject("message")
            assertEquals("42s", message["android"].asJsonObject["ttl"].asString)
        }
    }

    @Test
    fun conditionTarget() {
        JsonParser.parseString(FcmRequest {
            validateOnly(true)
            message {
                condition("abc")
            }
        }.payloadString()).asJsonObject.apply {
            val message = getAsJsonObject("message")
            assertEquals("abc", message["condition"].asString)
            assertNull(message["token"])
            assertNull(message["topic"])
        }
    }

    @Test
    fun tokenTarget() {
        JsonParser.parseString(FcmRequest {
            validateOnly(true)
            message {
                token("abc")
            }
        }.payloadString()).asJsonObject.apply {
            val message = getAsJsonObject("message")
            assertNull(message["condition"])
            assertEquals("abc", message["token"].asString)
            assertNull(message["topic"])
        }
    }

    @Test
    fun topicTarget() {
        JsonParser.parseString(FcmRequest {
            validateOnly(true)
            message {
                topic("abc")
            }
        }.payloadString()).asJsonObject.apply {
            val message = getAsJsonObject("message")
            assertNull(message["condition"])
            assertNull(message["token"])
            assertEquals("abc", message["topic"].asString)
        }
    }

    @Test
    fun nullMessage() {
        assertDoesNotThrow {
            FcmRequest { }
        }
    }

    @Test
    fun unsetTarget() {
        assertDoesNotThrow {
            FcmRequest {
                validateOnly(true)
                message {}
            }
        }
    }

    @Test
    fun twiceConditionTarget() {
        FcmRequest {
            validateOnly(true)
            message {
                condition("abc")
                assertDoesNotThrow {
                    condition("abc")
                }
            }
        }
    }

    @Test
    fun twiceTopicTarget() {
        FcmRequest {
            validateOnly(true)
            message {
                topic("abc")
                assertDoesNotThrow {
                    topic("abc")
                }
            }
        }
    }

    @Test
    fun twiceTokenTarget() {
        FcmRequest {
            validateOnly(true)
            message {
                token("abc")
                assertDoesNotThrow {
                    token("abc")
                }
            }
        }
    }

    @Test
    fun secondTarget() {
        FcmRequest {
            validateOnly(true)
            message {
                condition("abc")
                assertDoesNotThrow {
                    token("def")
                }
            }
        }
    }

    @Test
    fun propagateException() {
        val exception = RuntimeException("uh-oh!")
        runCatching {
            FcmRequest {
                throw exception
            }
        }.onFailure {
            if (it !== exception) {
                fail("Unexpected exception: $it")
            }
        }.onSuccess {
            fail("Unexpected success")
        }
    }
}
