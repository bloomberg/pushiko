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

import com.bloomberg.pushiko.http.netty.http2.newRequestHeaders
import io.netty.handler.codec.Headers
import io.netty.handler.codec.http.HttpScheme
import io.netty.util.AsciiString
import javax.annotation.concurrent.NotThreadSafe
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

private val emptyByteArray = ByteArray(0)

private val authorityPseudoHeader = AsciiString.cached(":authority")
private val methodPseudoHeader = AsciiString.cached(":method")
private val pathPseudoHeader = AsciiString.cached(":path")
private val schemePseudoHeader = AsciiString.cached(":scheme")

@OptIn(ExperimentalContracts::class)
public inline fun HttpRequest(block: HttpRequestBuilder.() -> Unit): HttpRequest {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return HttpRequestBuilder().apply(block).build()
}

@NotThreadSafe
public class HttpRequestBuilder @PublishedApi internal constructor() {
    private val headers = newRequestHeaders().apply { set(schemePseudoHeader, HttpScheme.HTTPS.name()) }
    private var body: ByteArray = emptyByteArray
    public var wantsResponseBody: Boolean = true

    public fun authority(value: CharSequence): HttpRequestBuilder = apply { headers.set(authorityPseudoHeader, value) }

    public fun method(value: CharSequence): HttpRequestBuilder = apply { headers.set(methodPseudoHeader, value) }

    public fun path(value: CharSequence): HttpRequestBuilder = apply { headers.set(pathPseudoHeader, value) }

    public fun header(key: CharSequence, value: CharSequence): HttpRequestBuilder = apply { headers.add(key, value) }

    public fun header(key: CharSequence, value: Int): HttpRequestBuilder = apply { headers.addInt(key, value) }

    public fun header(key: CharSequence, value: Long): HttpRequestBuilder = apply { headers.addLong(key, value) }

    public fun body(value: ByteArray): HttpRequestBuilder = apply { body = value }

    @JvmSynthetic @PublishedApi
    internal fun build(): HttpRequest = HttpRequest(headers, body, wantsResponseBody)
}

@Suppress("Detekt.UseDataClass")
public class HttpRequest internal constructor(
    internal val headers: Headers<CharSequence, CharSequence, *>,
    internal val body: ByteArray,
    internal val wantsResponseBody: Boolean = true
)
