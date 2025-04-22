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

import com.bloomberg.pushiko.http.exceptions.ChannelInactiveException
import com.bloomberg.pushiko.http.exceptions.ChannelStreamQuotaException
import com.bloomberg.pushiko.http.exceptions.ChannelWriteFailedException
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2Exception
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertTrue

internal class DefaultHttpRetryPolicyTest {
    @Test
    fun retriesChannelInactiveException() {
        assertTrue(DefaultHttpRetryPolicy.canRetryRequestAfter(ChannelInactiveException("")))
    }

    @Test
    fun retriesChannelStreamQuotaException() {
        assertTrue(DefaultHttpRetryPolicy.canRetryRequestAfter(ChannelStreamQuotaException("")))
    }

    @Test
    fun retriesChannelWriteFailedException() {
        assertTrue(DefaultHttpRetryPolicy.canRetryRequestAfter(ChannelWriteFailedException(IOException(""))))
    }

    @Test
    fun retriesHttp2ErrorRefusedStream() {
        assertTrue(DefaultHttpRetryPolicy.canRetryRequestAfter(
            Http2Exception.streamError(1, Http2Error.REFUSED_STREAM, "")))
    }

    @Test
    fun notRetryIOException() {
        assertFalse(DefaultHttpRetryPolicy.canRetryRequestAfter(IOException()))
    }

    @Test
    fun notRetryHttp2ErrorHttp1_1Required() {
        assertFalse(DefaultHttpRetryPolicy.canRetryRequestAfter(
            Http2Exception.streamError(1, Http2Error.HTTP_1_1_REQUIRED, "")))
    }
}
