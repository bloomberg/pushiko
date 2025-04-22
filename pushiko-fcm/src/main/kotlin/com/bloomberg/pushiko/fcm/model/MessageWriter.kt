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

import com.bloomberg.pushiko.commons.UNREACHABLE_KOTLIN_VERSION
import com.bloomberg.pushiko.fcm.annotations.FcmMarker
import com.bloomberg.pushiko.fcm.model.android.AndroidConfigWriter
import com.bloomberg.pushiko.json.JsonObjectWriter
import java.util.function.Consumer
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@FcmMarker
class MessageWriter @PublishedApi internal constructor(
    @get:JvmSynthetic @PublishedApi internal val writer: JsonObjectWriter
) {
    /**
     * Android specific options for messages sent through the FCM connection server.
     *
     * @since 0.1.0
     */
    @OptIn(ExperimentalContracts::class)
    @JvmSynthetic
    inline fun android(block: AndroidConfigWriter.() -> Unit): MessageWriter {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        writer.objectValue("android") { block(AndroidConfigWriter(this)) }
        return this
    }

    /**
     * @see android
     */
    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
    fun android(consumer: Consumer<AndroidConfigWriter>) = android { consumer.accept(this) }

    /**
     * Condition statement to send a message to, for example: "'abc' in topics && 'def' in topics".
     *
     * Exactly one of condition, token and topic must be non-null.
     *
     * @since 0.13.2
     *
     * @see <a href="https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#Message">projects.messages#Message</a>
     */
    fun condition(value: String) = apply {
        writer.stringValue("condition", value)
    }

    /**
     * Input only. Arbitrary key/value payload. The key should not be a reserved word.
     *
     * The maximum data payload size accepted by Firebase Cloud Messaging is 4096 bytes.
     *
     * @since 0.1.0
     *
     * @see <a href="https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#Message">projects.messages#Message</a>
     */
    @OptIn(ExperimentalContracts::class)
    @JvmSynthetic
    inline fun data(block: DataWriter.() -> Unit): MessageWriter {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        writer.objectValue("data") {
            block(DataWriter(this))
        }
        return this
    }

    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
    fun data(consumer: Consumer<DataWriter>) = data { consumer.accept(this) }

    /**
     * Basic notification template to use across all platforms.
     *
     * @since 0.1.0
     */
    @OptIn(ExperimentalContracts::class)
    @JvmSynthetic
    inline fun notification(block: NotificationWriter.() -> Unit): MessageWriter {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        writer.objectValue("notification") { block(NotificationWriter(this)) }
        return this
    }

    /**
     * @see notification
     */
    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
    fun notification(consumer: Consumer<NotificationWriter>) = notification { consumer.accept(this) }

    /**
     * Registration token uniquely identifying a message recipient.
     *
     * Exactly one of condition, token and topic must be non-null.
     *
     * @since 0.1.0
     *
     * @see <a href="https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#Message">projects.messages#Message</a>
     */
    fun token(value: String) = apply {
        writer.stringValue("token", value)
    }

    /**
     * Topic name to which a message will be sent for subscribers to receive. Prefixes like "/topics/" should
     * not be provided.
     *
     * Exactly one of condition, token and topic must be non-null.
     *
     * @since 0.13.2
     *
     * @see <a href="https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#Message">projects.messages#Message</a>
     */
    fun topic(value: String) = apply {
        writer.stringValue("topic", value)
    }
}
