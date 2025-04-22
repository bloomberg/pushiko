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

package com.bloomberg.pushiko.http

import com.bloomberg.pushiko.http.netty.EventLoopGroups.shutdownSharedEventLoopGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import javax.annotation.concurrent.GuardedBy
import kotlin.concurrent.thread

private suspend fun Iterable<HttpClient>.closeEach() = supervisorScope {
    forEach {
        launch(Dispatchers.Default) { it.close() }
    }
}

internal object HttpClientRegistry {
    private val lock = Any()
    @GuardedBy("lock")
    private var clients = mutableSetOf<HttpClient>()
    private val shutdownHook = thread(
        isDaemon = false,
        start = false
    ) {
        runBlocking {
            closeHttpClients()
            shutdownSharedEventLoopGroups()
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    fun add(client: HttpClient) = synchronized(lock) {
        clients.add(client)
    }

    fun remove(client: HttpClient) = synchronized(lock) {
        clients.remove(client)
    }

    private suspend fun closeHttpClients() {
        synchronized(lock) {
            clients.also { clients = mutableSetOf() }
        }.closeEach()
    }
}
