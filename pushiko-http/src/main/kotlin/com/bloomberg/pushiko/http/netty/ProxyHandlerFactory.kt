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

import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.netty.ktx.awaitKt
import io.netty.channel.EventLoop
import io.netty.handler.proxy.HttpProxyHandler
import io.netty.resolver.AddressResolver
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress

internal sealed interface ProxyHandlerFactory {
    @Throws(IOException::class)
    suspend fun createProxyHandler(): HttpProxyHandler
}

internal class HttpProxyHandlerFactory(
    private val eventLoop: EventLoop,
    private val resolver: AddressResolver<out SocketAddress>,
    private val socketAddress: InetSocketAddress
) : ProxyHandlerFactory {
    private val logger = Logger()

    init {
        if (!socketAddress.isUnresolved) {
            logger.info("Socket address is already resolved: {}", socketAddress)
        }
    }

    override suspend fun createProxyHandler(): HttpProxyHandler = eventLoop.newPromise<HttpProxyHandler>().apply {
        resolver.resolve(socketAddress).addListener {
            if (it.isSuccess) {
                val resolvedSocketAddress = it.now as SocketAddress
                logger.info("Proxy DNS resolved to {}", resolvedSocketAddress)
                trySuccess(HttpProxyHandler(resolvedSocketAddress))
            } else {
                logger.warn("Proxy DNS resolution for {} failed", socketAddress)
                tryFailure(it.cause())
            }
        }
    }.awaitKt()
}
