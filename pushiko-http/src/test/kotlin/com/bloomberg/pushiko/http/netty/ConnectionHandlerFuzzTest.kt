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

import com.code_intelligence.jazzer.junit.FuzzTest
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.io.ByteArrayOutputStream
import java.util.stream.Stream
import kotlin.math.min
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.provider.MethodSource

@Suppress("MagicNumber")
internal class ConnectionHandlerFuzzTest {
    @MethodSource("inputs")
    @FuzzTest
    fun fuzzResponseAccumulator(input: ByteArray) {
        if (input.size > MAX_INPUT_SIZE) {
            return
        }

        val body = newResponseBodyBuffer()
        val expected = ByteArrayOutputStream()
        var position = 0
        try {
            while (position < input.size) {
                val frameLength = min(MIN_FRAME_SIZE + (input[position].toInt() and 0xFF) * 32,
                    input.size - position)
                body.appendAndAssert(input.copyOfRange(position, position + frameLength), expected)
                position += frameLength
            }

            if (input.firstOrNull()?.toInt()?.and(BOUNDARY_CASE_FLAG) == BOUNDARY_CASE_FLAG) {
                val remainingCapacity = body.maxCapacity() - body.readableBytes()
                if (remainingCapacity > 0) {
                    body.appendAndAssert(ByteArray(remainingCapacity), expected)
                }
                body.appendAndAssert(byteArrayOf(input.last()), expected)
            }

            assertTrue(body.readableBytes() <= body.maxCapacity())
            assertContentEquals(expected.toByteArray(), body.toByteArray())
        } finally {
            assertTrue(body.release())
        }
    }

    private fun ByteBuf.appendAndAssert(frameBytes: ByteArray, expected: ByteArrayOutputStream) {
        val frame = Unpooled.wrappedBuffer(frameBytes)
        try {
            val accepted = tryAppendResponseData(frame)
            assertEquals(frameBytes.size <= maxCapacity() - expected.size(), accepted)
            assertEquals(0, frame.readerIndex())
            assertEquals(1, frame.refCnt())
            if (accepted) {
                expected.write(frameBytes)
            }
        } finally {
            assertTrue(frame.release())
        }
    }

    private fun ByteBuf.toByteArray(): ByteArray = ByteArray(readableBytes()).also {
        getBytes(readerIndex(), it)
    }

    companion object {
        private const val MAX_INPUT_SIZE = 4_096
        private const val MIN_FRAME_SIZE = 64
        private const val BOUNDARY_CASE_FLAG = 0x80

        @JvmStatic
        fun inputs(): Stream<ByteArray> = Stream.of(
            byteArrayOf(),
            byteArrayOf(1, 2, 3),
            byteArrayOf(BOUNDARY_CASE_FLAG.toByte()),
            byteArrayOf(BOUNDARY_CASE_FLAG.toByte(), 1, 2, 3)
        )
    }
}
