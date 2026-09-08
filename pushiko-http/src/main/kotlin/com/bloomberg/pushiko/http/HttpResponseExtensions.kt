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

package com.bloomberg.pushiko.http

import java.time.DateTimeException
import java.time.Instant
import java.time.format.DateTimeFormatter

public fun HttpResponse.retryAfterMillis(): Long? = retryAfterHeader?.toString()?.let(::parseRetryAfterMillis)

private fun parseRetryAfterMillis(value: String): Long? = if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
    retryAfterSecondsMillis(value)
} else {
    retryAfterDateMillis(value)
}

private fun retryAfterSecondsMillis(value: String): Long? {
    val seconds = value.toLongOrNull() ?: return null
    return try {
        Math.multiplyExact(seconds, 1_000L)
    } catch (_: ArithmeticException) {
        null
    }
}

private fun retryAfterDateMillis(value: String): Long? = try {
    Math.subtractExact(
        Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value)).toEpochMilli(),
        System.currentTimeMillis()
    ).coerceAtLeast(0L)
} catch (_: DateTimeException) {
    null
} catch (_: ArithmeticException) {
    null
}

private val HttpResponse.retryAfterHeader: CharSequence?
    get() = header("retry-after")
