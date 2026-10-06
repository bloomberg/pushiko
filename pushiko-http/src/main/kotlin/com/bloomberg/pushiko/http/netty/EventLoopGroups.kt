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

/*
 * Copyright (c) 2020 Jon Chambers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.bloomberg.pushiko.http.netty

import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.pushiko.commons.strings.commonPluralSuffix
import com.bloomberg.pushiko.netty.ktx.awaitKt
import io.netty.channel.EventLoopGroup
import io.netty.channel.IoEventLoopGroup
import io.netty.channel.IoHandler
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
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioSocketChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.annotation.concurrent.GuardedBy

private fun newIoHandlerFactory(): IoHandlerFactory = when {
    Epoll.isAvailable() -> EpollIoHandler.newFactory()
    KQueue.isAvailable() -> KQueueIoHandler.newFactory()
    else -> NioIoHandler.newFactory()
}

internal suspend fun Iterable<EventLoopGroup>.shutdownEach() = supervisorScope {
    forEach {
        launch(Dispatchers.Default) { it.shutdownGracefully().awaitKt() }
    }
}

internal object EventLoopGroups {
    private val lock = Any()
    private val logger = Logger()
    @GuardedBy("lock")
    private var sharedEventLoopGroups = mutableListOf<EventLoopGroup>()

    internal suspend fun shutdownSharedEventLoopGroups() {
        synchronized(lock) {
            sharedEventLoopGroups.also { sharedEventLoopGroups = mutableListOf() }
        }.shutdownEach()
    }

    val sharedEventLoopGroup: EventLoopGroup by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MultiThreadIoEventLoopGroup(newIoHandlerFactory()).apply {
            logger.info("Shared EventLoopGroup {} has {} executors using {}", this, executorCount(),
                socketChannelClass().simpleName)
            terminationFuture().addListener {
                if (it.isSuccess) {
                    logger.info("Successfully shutdown the shared EventLoopGroup")
                } else {
                    logger.info("Failed to shutdown the shared EventLoopGroup", it.cause())
                }
            }
        }.also {
            synchronized(lock) {
                sharedEventLoopGroups.add(it)
            }
        }
    }

    val sharedSingleEventLoopGroup: EventLoopGroup by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MultiThreadIoEventLoopGroup(1, newIoHandlerFactory()).apply {
            executorCount().let {
                logger.info("Single EventLoopGroup {} has {} executor{} using {}", this, it, it.commonPluralSuffix(),
                    socketChannelClass().simpleName)
            }
            terminationFuture().addListener {
                if (it.isSuccess) {
                    logger.info("Successfully shutdown the single EventLoopGroup")
                } else {
                    logger.info("Failed to shutdown the single EventLoopGroup", it.cause())
                }
            }
        }.also {
            synchronized(lock) {
                sharedEventLoopGroups.add(it)
            }
        }
    }
}

private fun EventLoopGroup.isIoType(type: Class<out IoHandler>) = (this as? IoEventLoopGroup)?.isIoType(type) == true

@JvmSynthetic
internal fun EventLoopGroup.datagramChannelClass() = when {
    isIoType(EpollIoHandler::class.java) -> EpollDatagramChannel::class.java
    isIoType(KQueueIoHandler::class.java) -> KQueueDatagramChannel::class.java
    isIoType(NioIoHandler::class.java) -> NioDatagramChannel::class.java
    else -> error("Unrecognised event loop group: $this")
}.asSubclass(DatagramChannel::class.java)

@JvmSynthetic
internal fun EventLoopGroup.socketChannelClass() = when {
    isIoType(EpollIoHandler::class.java) -> EpollSocketChannel::class.java
    isIoType(KQueueIoHandler::class.java) -> KQueueSocketChannel::class.java
    isIoType(NioIoHandler::class.java) -> NioSocketChannel::class.java
    else -> error("Unrecognised event loop group: $this")
}.asSubclass(SocketChannel::class.java)
