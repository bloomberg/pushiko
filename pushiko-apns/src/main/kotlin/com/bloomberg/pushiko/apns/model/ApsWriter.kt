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

import com.bloomberg.pushiko.apns.annotations.ApnsMarker
import com.bloomberg.pushiko.commons.UNREACHABLE_KOTLIN_VERSION
import com.bloomberg.pushiko.json.JsonObjectWriter
import java.util.function.Consumer
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * @since 0.18.0
 */
@ApnsMarker
public class ApsWriter @PublishedApi internal constructor(
    @get:JvmSynthetic @PublishedApi internal val writer: JsonObjectWriter
) {
    /**
     * @since 0.18.0
     */
    @OptIn(ExperimentalContracts::class)
    @JvmSynthetic
    public inline fun alert(block: AlertWriter.() -> Unit): ApsWriter {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        writer.objectValue("alert") { block(AlertWriter(this)) }
        return this
    }

    /**
     * @see [alert]
     *
     * @since 0.22.0
     */
    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
    public fun alert(consumer: Consumer<AlertWriter>): ApsWriter = alert(consumer::accept)

    public fun badge(value: Int): ApsWriter = apply {
        writer.intValue("badge", value)
    }

    public fun category(value: String): ApsWriter = apply {
        writer.stringValue("category", value)
    }

    public fun interruptionLevel(value: InterruptionLevel): ApsWriter = apply {
        writer.stringValue("interruption-level", value.value)
    }

    public fun isContentAvailable(value: Boolean): ApsWriter = apply {
        writer.intValue("content-available", if (value) { 1 } else { 0 })
    }

    public fun isMutableContent(value: Boolean): ApsWriter = apply {
        writer.intValue("mutable-content", if (value) { 1 } else { 0 })
    }

    public fun relevanceScore(value: Double): ApsWriter = apply {
        writer.doubleValue("relevance-score", value)
    }

    public fun sound(value: String): ApsWriter = apply {
        writer.stringValue("sound", value)
    }

    public fun targetContentId(value: String): ApsWriter = apply {
        writer.stringValue("target-content-id", value)
    }

    public fun threadId(value: String): ApsWriter = apply {
        writer.stringValue("thread-id", value)
    }
}
