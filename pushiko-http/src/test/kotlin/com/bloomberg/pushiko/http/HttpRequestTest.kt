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

package com.bloomberg.pushiko.http

import kotlin.test.Test
import kotlin.test.assertEquals

internal class HttpRequestTest {
    @Test
    fun httpRequest() {
        HttpRequest {
            method("POST")
            authority("fcm.googleapis.com")
            path("/send")
            header("authorization", "Bearer abc")
            body("{}".toByteArray(Charsets.UTF_8))
        }.run {
            assertEquals("POST", headers[":method"])
            assertEquals("fcm.googleapis.com", headers[":authority"])
            assertEquals("/send", headers[":path"])
            assertEquals("{}", String(body, Charsets.UTF_8))
        }
    }
}
