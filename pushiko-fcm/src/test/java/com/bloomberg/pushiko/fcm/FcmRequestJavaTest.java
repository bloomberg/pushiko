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

package com.bloomberg.pushiko.fcm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FcmRequestJavaTest {
    @Test
    void notification() {
        final FcmRequest request = FcmRequests.FcmRequest(it -> it.message(message -> message.token("abc123"))
            .message(messageWriter -> messageWriter.notification(
                notificationWriter -> notificationWriter.title("Hello")
                    .body("World")
                    .image("https://foo.com/icon")
                )
            ));
        final JsonObject object = (JsonObject) JsonParser.parseString(request.payloadString());
        final JsonObject notification = object.getAsJsonObject("message").getAsJsonObject("notification");
        assertEquals("Hello", notification.get("title").getAsString());
        assertEquals("World", notification.get("body").getAsString());
        assertEquals("https://foo.com/icon", notification.get("image").getAsString());
    }
}
