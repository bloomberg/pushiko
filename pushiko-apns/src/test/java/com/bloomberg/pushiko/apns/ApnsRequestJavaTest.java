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

package com.bloomberg.pushiko.apns;

import com.bloomberg.pushiko.apns.model.InterruptionLevel;
import com.bloomberg.pushiko.apns.model.Priority;
import com.bloomberg.pushiko.apns.model.PushType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.bloomberg.pushiko.apns.ApnsRequests.ApnsRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ApnsRequestJavaTest {
    @Test
    void createRequest() {
        final ApnsRequest request = ApnsRequest(builder -> builder.collapseId("foo")
            .deviceToken("abc123")
            .expiration(Instant.now())
            .id(UUID.randomUUID())
            .priority(Priority.IMMEDIATE)
            .pushType(PushType.ALERT)
            .topic("news")
            .aps(apsWriter -> apsWriter.badge(42)
                .category("alert")
                .interruptionLevel(InterruptionLevel.ACTIVE)
                .isContentAvailable(true)
                .isMutableContent(false)
                .relevanceScore(1.0)
                .sound("jingle")
                .targetContentId("finance")
                .threadId("my-thread")
                .alert(alertWriter -> alertWriter.title("Hello")
                    .body("World!")
                    .subtitle("Nice to meet you")))
            .payload(writer -> writer.stringValue("foo", "bar")
                .objectValue("inner", innerWriter -> innerWriter.intValue("answer", 42)))
        );
        assertEquals("abc123", request.deviceToken);
        assertEquals("foo", request.headers.collapseId);
        assertEquals("news", request.headers.topic);
        assertSame(PushType.ALERT, request.headers.pushType);
    }
}
