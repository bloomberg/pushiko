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

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

internal const val MAX_RESPONSE_BODY_BYTES = 256 * 1_024
private const val INITIAL_RESPONSE_BODY_CAPACITY = 256

internal fun newResponseBodyBuffer(): ByteBuf =
    Unpooled.buffer(INITIAL_RESPONSE_BODY_CAPACITY, MAX_RESPONSE_BODY_BYTES)

internal fun ByteBuf.tryAppendResponseData(data: ByteBuf): Boolean {
    val length = data.readableBytes()
    if (length > MAX_RESPONSE_BODY_BYTES - readableBytes()) {
        return false
    }
    writeBytes(data, data.readerIndex(), length)
    return true
}
