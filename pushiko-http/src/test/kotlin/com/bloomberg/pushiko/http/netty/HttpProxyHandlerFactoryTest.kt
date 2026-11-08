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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.bloomberg.pushiko.http.netty

import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.resolver.AddressResolver
import io.netty.resolver.dns.DefaultDnsServerAddressStreamProvider
import io.netty.resolver.dns.RoundRobinDnsAddressResolverGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import java.net.InetSocketAddress
import java.net.SocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class HttpProxyHandlerFactoryTest {
    private val eventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val resolverGroups = RoundRobinDnsAddressResolverGroup(
        eventLoopGroup.datagramChannelClass(),
        DefaultDnsServerAddressStreamProvider.INSTANCE
    )

    @AfterEach
    fun tearDown() {
        resolverGroups.use {
            eventLoopGroup.shutdownGracefully().get()
        }
    }

    @Test
    fun createProxyHandlerFromUnresolvedAddress(): Unit = runTest {
        HttpProxyHandlerFactory(
            eventLoopGroup.next(),
            resolverGroups.getResolver(eventLoopGroup.next()),
            InetSocketAddress.createUnresolved("localhost", 8888)
        ).createProxyHandler().apply {
            val address = proxyAddress<InetSocketAddress>()
            assertFalse(address.isUnresolved)
            assertEquals("localhost", address.hostName)
            assertEquals(8888, address.port)
        }
    }

    @Test
    fun createProxyHandlerFromResolvedAddress(): Unit = runTest {
        val proxyAddress = InetSocketAddress("127.0.0.1", 8888)
        val resolver = mock<AddressResolver<SocketAddress>>()

        HttpProxyHandlerFactory(
            eventLoopGroup.next(),
            resolver,
            proxyAddress
        ).createProxyHandler().apply {
            val address = proxyAddress<InetSocketAddress>()
            assertFalse(address.isUnresolved)
            assertEquals(proxyAddress, address)
        }
        verifyNoInteractions(resolver)
    }
}
