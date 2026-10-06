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

import io.netty.channel.EventLoopGroup
import io.netty.channel.IoHandlerFactory
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDatagramChannel
import io.netty.channel.epoll.EpollIoHandler
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueDatagramChannel
import io.netty.channel.kqueue.KQueueIoHandler
import io.netty.channel.kqueue.KQueueSocketChannel
import io.netty.channel.local.LocalIoHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioSocketChannel
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.mock

private inline fun <T> EventLoopGroup.use(block: (EventLoopGroup) -> T): T = try {
    block(this)
} finally {
    shutdownGracefully(0L, 0L, java.util.concurrent.TimeUnit.MILLISECONDS)
}

private fun ioGroup(factory: IoHandlerFactory) = MultiThreadIoEventLoopGroup(1, factory)

internal class EventLoopGroupsTest {
    @Test
    fun epollChannels() {
        assumeTrue(Epoll.isAvailable())
        ioGroup(EpollIoHandler.newFactory()).use {
            assertSame(EpollDatagramChannel::class.java, it.datagramChannelClass())
            assertSame(EpollSocketChannel::class.java, it.socketChannelClass())
        }
    }

    @Test
    fun kqueueChannels() {
        assumeTrue(KQueue.isAvailable())
        ioGroup(KQueueIoHandler.newFactory()).use {
            assertSame(KQueueDatagramChannel::class.java, it.datagramChannelClass())
            assertSame(KQueueSocketChannel::class.java, it.socketChannelClass())
        }
    }

    @Test
    fun nioChannels() {
        ioGroup(NioIoHandler.newFactory()).use {
            assertSame(NioDatagramChannel::class.java, it.datagramChannelClass())
            assertSame(NioSocketChannel::class.java, it.socketChannelClass())
        }
    }

    @Test
    fun shutdownTerminatesEveryGroup() = runTest {
        val groups = listOf(
            MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory()),
            ioGroup(NioIoHandler.newFactory()),
            ioGroup(
                when {
                    Epoll.isAvailable() -> EpollIoHandler.newFactory()
                    KQueue.isAvailable() -> KQueueIoHandler.newFactory()
                    else -> NioIoHandler.newFactory()
                }
            )
        )
        groups.shutdownEach()
        assertTrue(groups.all { it.isTerminated })
        groups.shutdownEach()
        assertTrue(groups.all { it.isTerminated })
    }

    @Test
    fun shutdownOfNoGroupsCompletes() = runTest {
        emptyList<EventLoopGroup>().shutdownEach()
    }

    @Test
    fun sharedGroupsHaveChannelClasses() {
        EventLoopGroups.sharedEventLoopGroup.socketChannelClass()
        EventLoopGroups.sharedSingleEventLoopGroup.datagramChannelClass()
    }

    @Test
    fun groupWithUnsupportedTransportIsRejected() {
        ioGroup(LocalIoHandler.newFactory()).use {
            assertFailsWith<IllegalStateException> { it.socketChannelClass() }
            assertFailsWith<IllegalStateException> { it.datagramChannelClass() }
        }
    }

    @Test
    fun groupWithoutIoHandlerIsRejected() {
        mock<EventLoopGroup>(stubOnly = true).let {
            assertFailsWith<IllegalStateException> { it.socketChannelClass() }
            assertFailsWith<IllegalStateException> { it.datagramChannelClass() }
        }
    }
}
