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

import com.bloomberg.pushiko.fcm.annotations.FcmMarker
import com.bloomberg.pushiko.json.JsonObjectWriter

/**
 * @see <a href="https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#Notification">projects.messages#Notification</a>
 */
@FcmMarker
class NotificationWriter @PublishedApi internal constructor(
    @get:JvmSynthetic @PublishedApi internal val writer: JsonObjectWriter
) {
    fun body(value: String) = apply {
        writer.stringValue("body", value)
    }

    /**
     * The URL of an image that is to be displayed in a notification.
     *
     * Quota restrictions and cost implications may apply.
     *
     * @since 0.26.3
     */
    fun image(value: String) = apply {
        writer.stringValue("image", value)
    }

    fun title(value: String) = apply {
        writer.stringValue("title", value)
    }
}
