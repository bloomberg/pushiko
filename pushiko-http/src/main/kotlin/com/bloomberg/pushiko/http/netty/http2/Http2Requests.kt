/*
 * Copyright 2026 Bloomberg Finance L.P.
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

package com.bloomberg.pushiko.http.netty.http2

import com.bloomberg.pushiko.http.HttpRequest
import io.netty.handler.codec.Headers
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.Http2Headers

/**
 * Request headers, validated on insertion. HTTP/2 and HTTP/3 share the same field name rules, so these serve as
 * protocol-neutral storage.
 */
internal fun newRequestHeaders(): Headers<CharSequence, CharSequence, *> = DefaultHttp2Headers()

internal val HttpRequest.http2Headers: Http2Headers
    get() = headers as Http2Headers
