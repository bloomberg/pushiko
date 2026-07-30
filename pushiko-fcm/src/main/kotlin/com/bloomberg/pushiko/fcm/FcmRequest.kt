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

@file:JvmName("FcmRequests")

package com.bloomberg.pushiko.fcm

import com.bloomberg.pushiko.commons.UNREACHABLE_KOTLIN_VERSION
import com.bloomberg.pushiko.fcm.annotations.FcmMarker
import com.bloomberg.pushiko.fcm.model.MessageWriter
import com.bloomberg.pushiko.json.JsonObjectWriter
import javax.annotation.concurrent.NotThreadSafe
import javax.annotation.concurrent.ThreadSafe
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import okio.Buffer
import java.util.function.Consumer

@OptIn(ExperimentalContracts::class)
@JvmSynthetic
public inline fun FcmRequest(block: FcmRequest.Builder.() -> Unit): FcmRequest {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return FcmRequest.Builder().run {
        runCatching {
            block(this)
            build()
        }.getOrElse {
            runCatching(::close)
            throw it
        }
    }
}

@Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
@SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
public fun FcmRequest(consumer: Consumer<FcmRequest.Builder>): FcmRequest = FcmRequest(consumer::accept)

@ThreadSafe
public class FcmRequest private constructor(
    @get:JvmSynthetic
    internal val payload: ByteArray
) {
    /**
     * @since 0.21.2
     */
    public fun payloadString(): String = String(payload, charset = Charsets.UTF_8)

    @FcmMarker
    @NotThreadSafe
    @OptIn(ExperimentalContracts::class)
    public class Builder @PublishedApi internal constructor() {
        @get:JvmSynthetic @PublishedApi
        internal var buffer: Buffer = Buffer()
        @get:JvmSynthetic @PublishedApi
        internal val writer: JsonObjectWriter = JsonObjectWriter(buffer)

        @JvmSynthetic @PublishedApi
        internal fun close() {
            try {
                writer.close()
            } finally {
                buffer.use(Buffer::clear)
            }
        }

        public fun validateOnly(value: Boolean): Builder = apply {
            writer.booleanValue("validate_only", value)
        }

        @JvmSynthetic
        public inline fun message(block: MessageWriter.() -> Unit): Builder {
            contract {
                callsInPlace(block, InvocationKind.EXACTLY_ONCE)
            }
            writer.objectValue("message") { block(MessageWriter(writer)) }
            return this
        }

        @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
        @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
        public fun message(consumer: Consumer<MessageWriter>): Builder = message(consumer::accept)

        @JvmSynthetic @PublishedApi
        internal fun build(): FcmRequest {
            writer.close()
            return FcmRequest(buffer.use(Buffer::readByteArray))
        }
    }
}
