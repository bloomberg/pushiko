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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Priority of a message to send to Android devices. Note this priority is an FCM concept that controls when the
 * message is delivered. Additionally, you can determine notification display priority on targeted Android devices
 * using [AndroidNotificationWriter.notificationPriority].
 *
 * @see <a href="https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#androidmessagepriority">projects.messages#androidmessagepriority</a>
 *
 * @since 0.1.0
 */
@Serializable
enum class AndroidMessagePriority(
    internal val value: String
) {
    /**
     * @since 0.1.0
     */
    @SerialName("high") HIGH("high"),
    /**
     * This is the default priority assumed by the FCM peer for data messages.
     *
     * @since 0.1.0
     */
    @SerialName("normal") NORMAL("normal")
}
