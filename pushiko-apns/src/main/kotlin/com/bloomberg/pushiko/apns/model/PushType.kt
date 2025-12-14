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

package com.bloomberg.pushiko.apns.model

/**
 * Type of push notification, accurately describing the contents of a notification's payload.
 *
 * @since 0.12.0
 */
enum class PushType(
    val value: String
) {
    /**
     * Use the alert push type for notifications that trigger a user interaction.
     *
     * @see <a href="https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/generating_a_remote_notification">Generating a remote notification</a>
     *
     * @since 0.12.0
     */
    ALERT("alert"),
    /**
     * Use the background push type for notifications that deliver content in the background, and don’t trigger
     * any user interactions. APNs treats background notifications as low priority and may throttle their delivery.
     * It is an error to use [Priority.IMMEDIATE] for background notifications.
     *
     * @since 0.12.0
     *
     * @see <a href="https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/pushing_background_updates_to_your_app">Pushing background updates to your app</a>
     * @see <a href="https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/sending_notification_requests_to_apns#2947607">Sending notification requests to APNs</a>
     */
    BACKGROUND("background"),
    /**
     * @since 0.12.0
     */
    COMPLICATION("complication"),
    /**
     * @since 0.12.0
     */
    FILE_PROVIDER("fileprovider"),
    /**
     * @since 0.12.0
     */
    LIVE_ACTIVITY("liveactivity"),
    /**
     * @since 0.12.0
     */
    LOCATION("location"),
    /**
     * @since 0.12.0
     */
    MDM("mdm"),
    /**
     * @since 0.12.0
     */
    VOIP("voip");
}
