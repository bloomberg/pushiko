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

import com.bloomberg.pushiko.commons.UNREACHABLE_KOTLIN_VERSION
import com.bloomberg.pushiko.fcm.annotations.FcmMarker
import com.bloomberg.pushiko.json.JsonObjectWriter
import java.util.function.Consumer
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * @see <a href="https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#AndroidConfig">projects.messages#AndroidConfig</a>
 */
@FcmMarker
public class AndroidConfigWriter @PublishedApi internal constructor(
    @get:JvmSynthetic @PublishedApi internal val writer: JsonObjectWriter
) {
    /**
     * An identifier of a group of messages that can be collapsed, so that only the last message gets sent when
     * delivery can be resumed. A maximum of 4 different collapse keys is allowed at any given time.
     * @param value of the notification's collapse key.
     * @return this writer.
     */
    public fun collapseKey(value: String): AndroidConfigWriter = apply {
        writer.stringValue("collapse_key", value)
    }

    /**
     * Message priority. Can either be "normal" or "high", or omitted. If omitted, the FCM peer will default to
     * "normal" for data messages and "high" for notification messages.
     * @param value priority of the notification.
     * @return this writer.
     * @see <a href="https://goo.gl/GjONJv">concept-options#setting-the-priority-of-a-message</a>
     */
    public fun priority(value: AndroidMessagePriority): AndroidConfigWriter = apply {
        writer.stringValue("priority", value.value)
    }

    /**
     * Package name of the application where the registration token must match in order to receive the message.
     * @param value of the notification's restricted package name.
     * @return this writer.
     */
    public fun restrictedPackageName(value: String): AndroidConfigWriter = apply {
        writer.stringValue("restricted_package_name", value)
    }

    /**
     * @param value duration in seconds with up to nine fractional digits, terminated by 's'. Example: "3.5s".
     * @return this writer.
     */
    public fun timeToLive(value: String): AndroidConfigWriter = apply {
        writer.stringValue("ttl", value)
    }

    /**
     * @param value duration in seconds.
     * @return this writer.
     * @see [timeToLive]
     */
    public fun timeToLiveSeconds(value: Long): AndroidConfigWriter = timeToLive("${value}s")

    @OptIn(ExperimentalContracts::class)
    @JvmSynthetic
    public inline fun notification(block: AndroidNotificationWriter.() -> Unit): AndroidConfigWriter {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        writer.objectValue("notification") { block(AndroidNotificationWriter(this)) }
        return this
    }

    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
    public fun notification(consumer: Consumer<AndroidNotificationWriter>): AndroidConfigWriter =
        notification(consumer::accept)
}
