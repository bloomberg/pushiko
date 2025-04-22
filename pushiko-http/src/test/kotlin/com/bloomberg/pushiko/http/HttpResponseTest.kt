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

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http2.DefaultHttp2Headers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class HttpResponseTest {
    @Test
    fun successHttpResponse() {
        HttpResponse(
            200,
            DefaultHttp2Headers().apply { add("content-id", "abc") },
            Unpooled.copiedBuffer("{}", Charsets.UTF_8)
        ).use {
            assertEquals(200, it.code)
            assertEquals("abc", it.header("content-id"))
            assertNull(it.header("foo"))
            assertEquals("{}", it.body!!.bufferedReader(Charsets.UTF_8).readText())
        }
    }

    @Test
    fun successHttpResponseNullBody() {
        HttpResponse(
            200,
            DefaultHttp2Headers().apply { add("content-id", "abc") }
        ).use {
            assertNull(it.body)
        }
    }
}
