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

package com.bloomberg.pushiko.fcm.model

import com.bloomberg.pushiko.json.JsonObjectWriter

/**
 * Write key-value string pairs that must have UTF-8 encoding.
 *
 * @see <a href="https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#resource:-message">projects.messages#resource:-message</a>
 */
class DataWriter(
    private val writer: JsonObjectWriter
) {
    /**
     * UTF-8 encoded key-value string pair. The key must not be a reserved word, which includes "from" and
     * "message_type", or start with "google" or "gcm".
     *
     * @param key UTF-8 encoded string.
     * @param value UTF-8 encoded string.
     *
     * @return this writer.
     *
     * @since 0.28.0
     */
    fun stringValue(key: String, value: String) = apply {
        writer.stringValue(key, value)
    }
}
