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
import java.util.concurrent.TimeUnit

fun HttpResponse.retryAfterMillis() = retryAfterHeader?.toString()?.let {
    try {
        TimeUnit.SECONDS.toMillis(it.toLong())
    } catch (_: NumberFormatException) {
        try {
            (Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(it)).toEpochMilli() - System.currentTimeMillis())
                .coerceAtLeast(0L)
        } catch (_: DateTimeException) {
            null
        }
    }
}

private val HttpResponse.retryAfterHeader: CharSequence?
    get() = header("retry-after")
