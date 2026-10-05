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

package com.bloomberg.pushiko.http.netty

import com.bloomberg.pushiko.http.netty.http2.isHttp2Error
import com.bloomberg.pushiko.http.netty.http2.isHttp2RefusedStream

/**
 * Whether the throwable is an error raised by the HTTP protocol codec.
 */
internal fun Throwable.isProtocolError(): Boolean = isHttp2Error()

/**
 * Whether the peer has signalled that it refused the request before performing any application processing, so the
 * request can be safely retried.
 */
internal fun Throwable.isRequestNotProcessed(): Boolean = isHttp2RefusedStream()
