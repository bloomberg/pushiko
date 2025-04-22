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

import com.bloomberg.pushiko.http.IHttpClientProperties
import com.bloomberg.pushiko.pool.Factory
import com.bloomberg.pushiko.pool.Recycler
import io.netty.channel.Channel
import javax.annotation.concurrent.ThreadSafe

@ThreadSafe
internal class PoolableChannelFactory(
    private val factory: ChannelFactory,
    private val httpProperties: IHttpClientProperties
) : Factory<PoolableChannel>, Recycler<Channel> {
    override val allocations: Int
        get() = factory.allChannelCountEstimate

    override suspend fun close() {
        factory.close()
    }

    override suspend fun make() = PoolableChannel(
        factory.make(),
        httpProperties
    )

    override fun recycle(obj: Channel) {
        obj.close()
    }
}

internal fun IHttpClientProperties.factoryConfiguration() = ChannelFactoryConfiguration(
    connectionRetryFuzzInterval = connectionFuzzInterval,
    idleInterval = idleConnectionInterval,
    isMonitored = isMonitorConnections,
    maximumAge = maximumConnectionAge,
    maximumConnectRetryAttempts = maximumConnectRetries
)
