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

/**
 * IETF's HTTP/2 specification recommends a concurrent request limit no smaller than 100.
 * See: https://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2
 */
internal val defaultWatermark = ConcurrentRequestWaterMark(
    low = 50L,
    high = 120L
)

internal data class ConcurrentRequestWaterMark(
    val low: Long,
    val high: Long
)
