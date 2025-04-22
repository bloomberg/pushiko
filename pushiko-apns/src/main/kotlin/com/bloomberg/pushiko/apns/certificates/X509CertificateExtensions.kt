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

package com.bloomberg.pushiko.apns.certificates

import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import kotlin.time.Duration
import kotlin.time.toJavaDuration

@JvmSynthetic
internal fun X509Certificate.willExpireWithin(duration: Duration) = notAfter.localDateTime()
    .isBefore(LocalDateTime.now().plus(duration.toJavaDuration()))

private fun Date.localDateTime() = toInstant()
    .atZone(ZoneId.systemDefault())
    .toLocalDateTime()
