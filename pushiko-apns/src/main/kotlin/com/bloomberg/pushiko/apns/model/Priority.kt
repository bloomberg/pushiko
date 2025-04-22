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

private const val PRIORITY_ONE = 1
private const val PRIORITY_FIVE = 5
private const val PRIORITY_TEN = 10

/**
 * @see <a href="https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/sending_notification_requests_to_apns">Sending requests to APNs</a>
 */
enum class Priority(
    val code: Int
) {
    /**
     * Send the notification based on power considerations on the user’s device.
     */
    CONSERVE_POWER(PRIORITY_FIVE),
    /**
     * Send the notification immediately.
     */
    IMMEDIATE(PRIORITY_TEN),
    /**
     * Send the notification to the device prioritizing the device's power above all other factors.
     */
    PRIORITIZE_POWER(PRIORITY_ONE)
}
