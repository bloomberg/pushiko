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

package com.bloomberg.pushiko.fcm.model.android

import com.bloomberg.pushiko.fcm.annotations.FcmMarker
import com.bloomberg.pushiko.json.JsonObjectWriter

/**
 * @see <a href="https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#androidnotification">AndroidNotification</a>
 */
@FcmMarker
class AndroidNotificationWriter @PublishedApi internal constructor(
    @get:JvmSynthetic @PublishedApi internal val writer: JsonObjectWriter
) {
    /**
     * Whether to play the Android framework's default sound when the notification is received by the device.
     *
     * @since 0.28.0
     */
    fun defaultSound(value: Boolean) = apply {
        writer.booleanValue("default_sound", value)
    }

    /**
     * The URL of an image that is to be displayed in a notification. If present this overrides the URL
     * [com.bloomberg.pushiko.fcm.model.NotificationWriter.image].
     *
     * Quota restrictions and cost implications may apply.
     *
     * @since 0.26.3
     */
    fun image(value: String) = apply {
        writer.stringValue("image", value)
    }

    /**
     * Set the relative priority for this notification, indicating how much of the user's attention this notification
     * warrants. This priority differs from [AndroidMessagePriority], being processed by the client after the message has
     * been received rather controlling when the message is delivered to the device.
     *
     * @since 0.1.0
     */
    fun notificationPriority(value: NotificationPriority) = apply {
        writer.stringValue("notification_priority", value.value)
    }

    /**
     * Play a sound when the device receives a notification.
     *
     * @param value "default" or the filename of a sound resource bundled in "/res/raw".
     *
     * @since 0.28.0
     */
    @JvmOverloads
    fun sound(value: String = "default") = apply {
        writer.stringValue("sound", value)
    }

    /**
     * @since 0.26.3
     */
    fun visibility(value: Visibility) = apply {
        writer.stringValue("visibility", value.value)
    }
}
