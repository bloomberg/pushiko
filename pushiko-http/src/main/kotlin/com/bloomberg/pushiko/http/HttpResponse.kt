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

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import io.netty.handler.codec.http2.Http2Headers
import java.io.Closeable
import java.io.InputStream
import javax.annotation.concurrent.NotThreadSafe

@NotThreadSafe
class HttpResponse private constructor(
    val code: Int,
    private val headers: Http2Headers,
    val body: InputStream? = null
) : Closeable {
    internal constructor(
        code: Int,
        headers: Http2Headers,
        body: ByteBuf? = null
    ) : this(
        code,
        headers,
        body?.let { ByteBufInputStream(it.retain(), true) }
    )

    fun header(key: CharSequence): CharSequence? = headers[key]

    override fun close() {
        body?.close()
    }
}
