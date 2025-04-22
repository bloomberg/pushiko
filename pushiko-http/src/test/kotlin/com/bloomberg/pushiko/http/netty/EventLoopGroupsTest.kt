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

import io.netty.channel.epoll.EpollDatagramChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.kqueue.KQueueDatagramChannel
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueSocketChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioSocketChannel
import org.junit.jupiter.api.Test
import kotlin.test.assertSame

internal class EventLoopGroupsTest {
    @Test
    fun epollDatagramChannel() {
        assertSame(EpollDatagramChannel::class.java, EpollEventLoopGroup::class.java.canonicalName.datagramChannelClass())
    }

    @Test
    fun kqueueDatagramChannel() {
        assertSame(KQueueDatagramChannel::class.java, KQueueEventLoopGroup::class.java.canonicalName.datagramChannelClass())
    }

    @Test
    fun nioDatagramChannel() {
        assertSame(NioDatagramChannel::class.java, NioEventLoopGroup::class.java.canonicalName.datagramChannelClass())
    }

    @Test
    fun epollSocketChannel() {
        assertSame(EpollSocketChannel::class.java, EpollEventLoopGroup::class.java.canonicalName.socketChannelClass())
    }

    @Test
    fun kqueueSocketChannel() {
        assertSame(KQueueSocketChannel::class.java, KQueueEventLoopGroup::class.java.canonicalName.socketChannelClass())
    }

    @Test
    fun nioSocketChannel() {
        assertSame(NioSocketChannel::class.java, NioEventLoopGroup::class.java.canonicalName.socketChannelClass())
    }
}
