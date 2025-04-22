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

package com.bloomberg.pushiko.fcm

import com.bloomberg.pushiko.fcm.model.android.AndroidMessagePriority
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.infra.Blackhole

internal open class FcmRequestBenchmark {
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun encodeRequest(blackhole: Blackhole) {
        blackhole.consume(FcmRequest {
            validateOnly(true)
            message {
                token("fwtQGQ7DO4I:APA91bEssxwiDvRa0fci2p559Tf4L9IUG5-F8cflp3ZhH-dT9WCkEXw5O4srE4SeZmCoDxXWk6_5ZLH_u" +
                    "-UdavFe9kFS2LT2w8p4sCkSs6UJLWHcBqI3FuXszlk2WLp-xXli3AEj7KRy")
                notification {
                    title("Hello")
                    body("World!")
                }
                android {
                    priority(AndroidMessagePriority.NORMAL)
                }
                data {
                    stringValue("Stay", "Safe!")
                }
            }
        })
    }
}
