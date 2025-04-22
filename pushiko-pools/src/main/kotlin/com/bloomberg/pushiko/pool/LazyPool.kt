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

package com.bloomberg.pushiko.pool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.reactor.mono
import reactor.core.publisher.Mono
import java.util.concurrent.ExecutorService
import java.util.function.Function
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

fun <R : Any, P : Poolable<R>> SuspendPool<R, P>.asLazyPool(
    executor: ExecutorService? = null
): LazyPool<R> = object : LazyPool<R> {
    private val dispatcher = executor?.asCoroutineDispatcher() ?: Dispatchers.Default

    override fun close(): Mono<*> = mono(dispatcher) {
        this@asLazyPool.close()
    }

    override fun <T : Any> withPermit(
        acquisitionTimeout: Duration,
        block: Function<R, T>
    ): Mono<T> = mono(dispatcher) {
        this@asLazyPool.withPermit(acquisitionTimeout) {
            block.apply(it)
        }
    }

    override fun prepare(): Mono<Int> = mono(dispatcher) {
        this@asLazyPool.prepare()
    }

    override fun <T : Any> withPermit(
        acquisitionTimeout: java.time.Duration,
        block: Function<R, T>
    ) = withPermit(acquisitionTimeout.toKotlinDuration(), block)

    override fun testAcquisition(
        acquisitionTimeout: Duration
    ): Mono<*> = mono(dispatcher) {
        this@asLazyPool.testAcquisition(acquisitionTimeout)
    }

    override fun testAcquisition(
        acquisitionTimeout: java.time.Duration
    ) = testAcquisition(acquisitionTimeout.toKotlinDuration())
}

interface LazyPool<R : Any> {
    fun close(): Mono<*>

    fun <T : Any> withPermit(
        acquisitionTimeout: Duration,
        block: Function<R, T>
    ): Mono<T>

    fun <T : Any> withPermit(
        acquisitionTimeout: java.time.Duration,
        block: Function<R, T>
    ): Mono<T>

    fun prepare(): Mono<*>

    fun testAcquisition(acquisitionTimeout: Duration): Mono<*>

    fun testAcquisition(acquisitionTimeout: java.time.Duration): Mono<*>
}
