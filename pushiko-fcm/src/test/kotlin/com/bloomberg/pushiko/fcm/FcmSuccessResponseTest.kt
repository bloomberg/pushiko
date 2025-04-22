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

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

internal class FcmSuccessResponseTest {
    /**
     * Sample response from Firebase HTTP v1 looks like the following.
     * <pre>
     * {@code
     *   {
     *     "name": "projects/foo/messages/0:1590670743188705%12fb227c12fb227d"
     *   }
     * }
     * </pre>
     */
    @Test
    fun ok() {
        Json.decodeFromString<FcmSuccessResponse>(
"""{
  "name": "projects/foo/messages/0:1590670743188705%12fb227c12fb227d"
}
""".trimIndent()).apply {
            assertEquals("projects/foo/messages/0:1590670743188705%12fb227c12fb227d", name)
        }
    }
}
