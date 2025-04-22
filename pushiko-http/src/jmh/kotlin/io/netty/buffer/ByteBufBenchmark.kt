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

package io.netty.buffer

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

internal open class ByteBufBenchmark {
    @State(Scope.Thread)
    open class Buffers {
        val source = ByteArray(128) { 0x42 }
        val directPooled: ByteBuf = PooledByteBufAllocator.DEFAULT.directBuffer(source.size)
        val heapPooled: ByteBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(source.size)

        @TearDown(Level.Trial)
        fun tearDown() {
            directPooled.release()
            heapPooled.release()
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun directWrite(input: Buffers) {
        input.directPooled.writeBytes(input.source)
        input.directPooled.resetWriterIndex()
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun heapWrite(input: Buffers) {
        input.heapPooled.writeBytes(input.source)
        input.heapPooled.resetWriterIndex()
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun directWriteReleased(input: Buffers) {
        PooledByteBufAllocator.DEFAULT.directBuffer(input.source.size).apply {
            try {
                writeBytes(input.source)
            } finally {
                resetWriterIndex()
                release()
            }
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun heapWriteReleased(input: Buffers) {
        PooledByteBufAllocator.DEFAULT.heapBuffer(input.source.size).apply {
            try {
                writeBytes(input.source)
            } finally {
                resetWriterIndex()
                release()
            }
        }
    }
}
