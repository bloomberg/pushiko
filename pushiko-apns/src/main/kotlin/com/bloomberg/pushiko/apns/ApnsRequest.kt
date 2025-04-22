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

@file:JvmName("ApnsRequests")

package com.bloomberg.pushiko.apns

import com.bloomberg.pushiko.apns.annotations.ApnsMarker
import com.bloomberg.pushiko.apns.model.Priority
import com.bloomberg.pushiko.apns.model.PushType
import com.bloomberg.pushiko.apns.model.ApsWriter
import com.bloomberg.pushiko.commons.UNREACHABLE_KOTLIN_VERSION
import com.bloomberg.pushiko.json.JsonObjectWriter
import okio.Buffer
import java.time.Instant
import java.util.UUID
import java.util.function.Consumer
import javax.annotation.concurrent.NotThreadSafe
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * @since 0.12.0
 */
@OptIn(ExperimentalContracts::class)
@JvmSynthetic
inline fun ApnsRequest(block: ApnsRequest.Builder.() -> Unit): ApnsRequest {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return ApnsRequest.Builder().run {
        runCatching {
            block(this)
            build()
        }.getOrElse {
            runCatching { close() }
            throw it
        }
    }
}

@Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
@SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
fun ApnsRequest(consumer: Consumer<ApnsRequest.Builder>) = ApnsRequest { consumer.accept(this) }

/**
 * @since 0.12.0
 */
class ApnsRequest private constructor(
    /**
     * The device token that identifies the user device to receive the notification.
     */
    @JvmField
    val deviceToken: String,
    @JvmField
    val headers: Headers,
    @get:JvmSynthetic
    @JvmField
    internal val payload: ByteArray
) {
    init {
        require(deviceToken.isNotEmpty()) { "Device token must not be empty" }
    }

    data class Headers internal constructor(
        /**
         * An identifier for coalescing multiple notifications into a single notification for the user.
         */
        @JvmField
        val collapseId: String? = null,
        /**
         * The time at which the notification is no longer valid.
         */
        @JvmField
        val expiration: Instant? = null,
        /**
         * A canonical UUID that is the unique ID for the notification. If an error occurs when sending the
         * notification, APNs includes this value when reporting the error.
         */
        @JvmField
        val id: UUID? = null,
        /**
         * The priority of the notification. If you omit this header, APNs sets the notification priority to 10.
         */
        @JvmField
        val priority: Priority? = null,
        /**
         * The push type must accurately reflect the contents of a notification’s payload. It is required for watchOS,
         * and recommended for iOS and macOS.
         */
        @JvmField
        val pushType: PushType? = null,
        /**
         * The topic for the notification, typically the topic is an app bundle identifier.
         */
        @JvmField
        val topic: String
    )

    /**
     * @since 0.18.3
     */
    fun payloadString() = String(payload, charset = Charsets.UTF_8)

    /**
     * @since 0.12.0
     */
    @ApnsMarker
    @NotThreadSafe
    class Builder @PublishedApi internal constructor() {
        @get:JvmSynthetic @PublishedApi
        internal val buffer = Buffer()
        @get:JvmSynthetic @PublishedApi
        internal val writer = JsonObjectWriter(buffer)

        private var collapseId: String? = null
        private lateinit var deviceToken: String
        private var expiration: Instant? = null
        private var id: UUID? = null
        private var priority: Priority? = null
        private var pushType: PushType? = null
        private lateinit var topic: String

        @JvmSynthetic @PublishedApi
        internal fun close() {
            try {
                writer.close()
            } finally {
                buffer.use {
                    it.clear()
                }
            }
        }

        /**
         * @since 0.18.0
         */
        @OptIn(ExperimentalContracts::class)
        @JvmSynthetic
        inline fun payload(block: JsonObjectWriter.() -> Unit): Builder {
            contract {
                callsInPlace(block, InvocationKind.EXACTLY_ONCE)
            }
            block(writer)
            return this
        }

        /**
         * @see [payload]
         *
         * @since 0.22.0
         */
        @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
        @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
        fun payload(consumer: Consumer<JsonObjectWriter>) = payload { consumer.accept(this) }

        /**
         * @since 0.18.0
         */
        @OptIn(ExperimentalContracts::class)
        @JvmSynthetic
        inline fun aps(block: ApsWriter.() -> Unit): Builder {
            contract {
                callsInPlace(block, InvocationKind.EXACTLY_ONCE)
            }
            writer.objectValue("aps") { block(ApsWriter(this)) }
            return this
        }

        /**
         * @see [aps]
         *
         * @since 0.22.0
         */
        @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
        @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
        fun aps(consumer: Consumer<ApsWriter>) = aps { consumer.accept(this) }

        fun collapseId(value: String) = apply {
            collapseId = value
        }

        fun deviceToken(value: String) = apply {
            deviceToken = value
        }

        fun expiration(value: Instant) = apply {
            expiration = value
        }

        fun id(value: UUID) = apply {
            id = value
        }

        fun priority(value: Priority) = apply {
            priority = value
        }

        fun pushType(value: PushType) = apply {
            pushType = value
        }

        fun topic(value: String) = apply {
            topic = value
        }

        @JvmSynthetic @PublishedApi
        internal fun build(): ApnsRequest {
            writer.close()
            return ApnsRequest(
                deviceToken,
                Headers(
                    collapseId,
                    expiration,
                    id,
                    priority,
                    pushType,
                    topic
                ),
                buffer.use { it.readByteArray() }
            )
        }
    }
}
