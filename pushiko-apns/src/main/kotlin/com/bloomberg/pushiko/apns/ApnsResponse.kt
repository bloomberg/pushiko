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

package com.bloomberg.pushiko.apns

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_OK
import java.time.Instant

sealed interface ApnsResponse

@Serializable
data class ApnsClientErrorResponse(
    @JvmField
    val reason: String?,
    private val timestamp: Long? = null,
    @Transient @JvmSynthetic internal var apnsId: CharSequence = "",
    @Transient @JvmSynthetic internal var apnsUniqueId: CharSequence? = null,
    @Transient @JvmSynthetic internal var code: Int = HTTP_BAD_REQUEST
) : ApnsResponse {
    @Transient
    private val timestampInstant = timestamp?.let { Instant.ofEpochMilli(it) }

    fun apnsId() = apnsId

    fun code() = code

    /**
     * The time at which APNs confirmed the device token was no longer valid for the topic.
     */
    fun timestamp() = timestampInstant
}

data class ApnsServerErrorResponse(
    @JvmField
    val code: Int,
    @JvmField
    val body: String?
) : ApnsResponse

data class ApnsSuccessResponse(
    @JvmField
    val apnsId: CharSequence,
    /**
     * In the development environment, APNs responds with this header. It can be used to find the APNs delivery
     * event logs for the associated notification in the APNs Push Notification Console.
     *
     * @see <a href="https://developer.apple.com/documentation/usernotifications/testing_notifications_using_the_push_notification_console">Testing notifications using the Push Notification Console</a>
     */
    @JvmField
    val apnsUniqueId: CharSequence?,
    @JvmField
    val code: Int = HTTP_OK
) : ApnsResponse
