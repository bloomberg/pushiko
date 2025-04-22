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

package com.bloomberg.pushiko.apns

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.Blackhole

internal open class ApnsRequestBenchmark {
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun requestWithStatic(blackhole: Blackhole) {
        blackhole.consume(ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            aps {
                alert {
                    title("Hello")
                    body("World!")
                }
            }
        }.run {
            payloadString()
        })
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun requestWithDynamic(blackhole: Blackhole) {
        blackhole.consume(ApnsRequest {
            deviceToken("abc123")
            topic("com.foo")
            payload {
                objectValue("aps") {
                    objectValue("alert") {
                        stringValue("title", "Hello")
                        stringValue("body", "World!")
                    }
                }
            }
        }.run {
            payloadString()
        })
    }
}
