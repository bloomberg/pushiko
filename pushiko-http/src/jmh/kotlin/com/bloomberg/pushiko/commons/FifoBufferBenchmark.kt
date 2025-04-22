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

package com.bloomberg.pushiko.commons

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

internal open class FifoBufferBenchmark {
    @State(Scope.Thread)
    open class Collections {
        val singleBuffer = FifoBuffer<String>(1)
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun addThenRemove(input: Collections) = input.singleBuffer.run {
        addLast("").also { removeFirstOrNull() }
    }
}

internal open class ArrayDequeBenchmark {
    @State(Scope.Thread)
    open class Collections {
        val singleArrayDeque = ArrayDeque<String>(1)
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun addThenRemove(input: Collections) = input.singleArrayDeque.run {
        add("").also { removeFirstOrNull() }
    }
}
