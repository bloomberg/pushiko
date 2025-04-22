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

package com.bloomberg.pushiko.http.netty

import com.bloomberg.pushiko.http.HttpRetryPolicy
import com.bloomberg.pushiko.http.exceptions.ChannelInactiveException
import com.bloomberg.pushiko.http.exceptions.ChannelStreamQuotaException
import com.bloomberg.pushiko.http.exceptions.ChannelWriteFailedException
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2Exception
import javax.annotation.concurrent.ThreadSafe

/**
 * Whether a sending a request can be safely retried after meeting with an exception.
 *
 * Quoting RFC-7540: From https://httpwg.org/specs/rfc7540.html#REFUSED_STREAM
 * "7. Error Codes
 * ...
 * REFUSED_STREAM (0x7): The endpoint refused the stream prior to performing any application processing (see Section 8.1.4
 * for details)."
 * From https://httpwg.org/specs/rfc7540.html#Reliability
 * "8.1.4. Request Reliability Mechanisms in HTTP/2
 * ...
 * The REFUSED_STREAM error code can be included in an RST_STREAM frame to indicate that the stream is being closed prior
 * to any processing having occurred. Any request that was sent on the reset stream can be safely retried."
 *
 * @return true if sending a request can safely be retried after meeting with an exception.
 */
@ThreadSafe
object DefaultHttpRetryPolicy : HttpRetryPolicy {
    override fun canRetryRequestAfter(throwable: Throwable): Boolean = when (throwable) {
        is ChannelInactiveException,
        is ChannelStreamQuotaException,
        is ChannelWriteFailedException -> true
        is Http2Exception.StreamException -> Http2Error.REFUSED_STREAM === throwable.error()
        else -> false
    }
}
