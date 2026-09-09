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

package com.bloomberg.pushiko.http

import com.code_intelligence.jazzer.junit.FuzzTest
import io.netty.handler.codec.http2.DefaultHttp2Headers
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.provider.MethodSource

internal class HttpResponseExtensionsFuzzTest {
    @MethodSource("inputs")
    @FuzzTest
    fun fuzzRetryAfter(input: ByteArray) {
        if (input.size > MAX_INPUT_SIZE) {
            return
        }

        val header = input.toString(Charsets.UTF_8)
        val actual = HttpResponse(503, DefaultHttp2Headers().add("retry-after", header)).retryAfterMillis()
        assertTrue(actual == null || actual >= 0L)

        if (header.isNotEmpty() && header.all { it in '0'..'9' }) {
            assertEquals(header.toLongOrNull()?.timesWithoutOverflow(1_000L), actual)
        }
    }

    private fun Long.timesWithoutOverflow(multiplier: Long): Long? = try {
        Math.multiplyExact(this, multiplier)
    } catch (_: ArithmeticException) {
        null
    }

    companion object {
        private const val MAX_INPUT_SIZE = 4_096

        @JvmStatic
        fun inputs(): Stream<ByteArray> = Stream.of(
            byteArrayOf(),
            "0".toByteArray(),
            "30".toByteArray(),
            "9223372036854775".toByteArray(),
            "9223372036854776".toByteArray(),
            "Fri, 31 Dec 9999 23:59:59 GMT".toByteArray(),
            byteArrayOf(0xC0.toByte(), 0x80.toByte())
        )
    }
}
