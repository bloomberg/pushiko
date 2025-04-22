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
 * Priority levels of a notification.
 *
 * @see <a href="https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#notificationpriority">projects.messages#notificationpriority</a>
 *
 * @since 0.1.0
 */
@Serializable
enum class NotificationPriority(
    internal val value: String
) {
    /**
     * Default notification priority. If the application does not prioritize its own notifications, use this value for
     * all notifications.
     *
     * @since 0.1.0
     */
    @SerialName("PRIORITY_DEFAULT") DEFAULT("PRIORITY_DEFAULT"),
    /**
     * Higher notification priority. Use this for more important notifications or alerts. The UI may choose to show
     * these notifications larger, or at a different position in the notification lists, compared with notifications
     * with PRIORITY_DEFAULT.
     *
     * @since 0.1.0
     */
    @SerialName("PRIORITY_HIGH") HIGH("PRIORITY_HIGH"),
    /**
     * Lower notification priority. The UI may choose to show the notifications smaller, or at a different position in
     * the list, compared with notifications with PRIORITY_DEFAULT
     *
     * @since 0.1.0
     */
    @SerialName("PRIORITY_LOW") LOW("PRIORITY_LOW"),
    /**
     * Highest notification priority. Use this for the application's most important items that require the user's
     * prompt attention or input.
     *
     * @since 0.1.0
     */
    @SerialName("PRIORITY_MAX") MAX("PRIORITY_MAX"),
    /**
     * Lowest notification priority. Notifications with this PRIORITY_MIN might not be shown to the user except under
     * special circumstances, such as detailed notification logs.
     *
     * @since 0.1.0
     */
    @SerialName("PRIORITY_MIN") MIN("PRIORITY_MIN"),
    /**
     * If priority is unspecified, notification priority is set to PRIORITY_DEFAULT.
     *
     * @since 0.1.0
     */
    @SerialName("PRIORITY_UNSPECIFIED") UNSPECIFIED("PRIORITY_UNSPECIFIED")
}
