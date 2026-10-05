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

import com.bloomberg.pushiko.http.IHttpClientProperties
import io.netty.channel.Channel
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

internal class FlakyPoolableChannelFactory(
    factory: ChannelFactory,
    httpProperties: IHttpClientProperties,
    private val creationFailureRate: Double,
    private val recycleFailureRate: Double
) : PoolableChannelFactory(factory, httpProperties) {
    private val creationFailureCounter = AtomicInteger()
    private val recycleFailureCounter = AtomicInteger()

    val creationFailureCount: Int
        get() = creationFailureCounter.get()

    val recycleFailureCount: Int
        get() = recycleFailureCounter.get()

    override suspend fun make(): PoolableChannel {
        if (ThreadLocalRandom.current().nextDouble() < creationFailureRate) {
            creationFailureCounter.incrementAndGet()
            throw IOException("Injected channel-creation fault")
        }
        return super.make()
    }

    override fun recycle(obj: Channel) {
        super.recycle(obj)
        if (ThreadLocalRandom.current().nextDouble() < recycleFailureRate) {
            recycleFailureCounter.incrementAndGet()
            error("Injected recycle fault")
        }
    }
}
