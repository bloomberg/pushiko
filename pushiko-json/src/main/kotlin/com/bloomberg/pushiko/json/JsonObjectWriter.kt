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

package com.bloomberg.pushiko.json

import com.bloomberg.pushiko.commons.UNREACHABLE_KOTLIN_VERSION
import com.squareup.moshi.JsonWriter
import okio.BufferedSink
import java.util.function.Consumer
import javax.annotation.concurrent.NotThreadSafe
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@DslMarker
annotation class JsonMarker

@OptIn(ExperimentalContracts::class)
@JvmSynthetic
internal inline fun <R> JsonObjectWriter.use(block: JsonObjectWriter.() -> R): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return try {
        block(this)
    } finally {
        close()
    }
}

/**
 * @since 0.18.0
 */
@JsonMarker
@NotThreadSafe
class JsonObjectWriter(sink: BufferedSink) {
    @get:JvmSynthetic @JvmField @PublishedApi
    internal val writer = JsonWriter.of(sink).apply {
        beginObject()
    }

    /**
     * Whether null objects are serialized. The default is false.
     */
    var serializeNulls: Boolean
        get() = writer.serializeNulls
        set(value) { writer.serializeNulls = value }

    fun close() {
        writer.use {
            it.endObject()
        }
    }

    fun booleanValue(key: String, value: Boolean) = apply {
        writer.name(key).value(value)
    }

    fun intValue(key: String, value: Int) = apply {
        writer.name(key).value(value)
    }

    fun longValue(key: String, value: Long) = apply {
        writer.name(key).value(value)
    }

    fun doubleValue(key: String, value: Double) = apply {
        writer.name(key).value(value)
    }

    fun stringValue(key: String, value: String) = apply {
        writer.name(key).value(value)
    }

    fun numericalValue(key: String, value: Number) = apply {
        writer.name(key).value(value)
    }

    fun nullValue(key: String) = apply {
        writer.name(key).nullValue()
    }

    /**
     * Encodes this key name but treats the value as a valid JSON literal, writing its contents directly without
     * performing any encoding.
     *
     * @param value to write directly.
     * @return this JsonObjectWriter.
     */
    fun literalValue(key: String, value: String) = apply {
        writer.name(key).valueSink().use {
            it.writeUtf8(value)
        }
    }

    /**
     * Encodes this key name but treats the value as a valid JSON literal, writing its contents directly without
     * performing any encoding.
     *
     * @param value to write directly.
     * @return this JsonObjectWriter.
     */
    fun literalValue(key: String, value: ByteArray) = apply {
        writer.name(key).valueSink().use {
            it.write(value)
        }
    }

    @OptIn(ExperimentalContracts::class)
    @JvmSynthetic
    inline fun objectValue(key: String, block: JsonObjectWriter.() -> Unit): JsonObjectWriter {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        writer.name(key).beginObject().apply {
            block(this@JsonObjectWriter)
        }.endObject()
        return this
    }

    /**
     * @see [objectValue]
     *
     * @since 0.22.0
     */
    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
    fun objectValue(key: String, consumer: Consumer<JsonObjectWriter>) = objectValue(key) { consumer.accept(this) }
}
