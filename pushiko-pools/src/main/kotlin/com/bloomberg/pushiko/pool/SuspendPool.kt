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

@file:OptIn(ExperimentalContracts::class, ExperimentalContracts::class)

package com.bloomberg.pushiko.pool

import javax.annotation.concurrent.ThreadSafe
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@JvmSynthetic
internal suspend fun <R : Any, P : Poolable<R>, T : Any> SuspendPool<R, P>.use(
    acquisitionTimeout: Duration,
    block: (R) -> T
): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return try {
        withPermit(acquisitionTimeout, block)
    } finally {
        close()
    }
}

@ThreadSafe
@Suppress("TooManyFunctions")
sealed class SuspendPool<R : Any, P : Poolable<R>>(
    name: String = "Pushiko"
) {
    private val scopeGroup = SingleThreadScopeGroup(name)

    protected val isWorkActive: Boolean
        get() = scopeGroup.isWorkActive

    @JvmField
    val metricsComponent = MetricsComponent()

    @JvmSynthetic
    suspend fun close() {
        scopeGroup.close()
        performClose()
    }

    @JvmSynthetic
    suspend inline fun <T : Any> withPermit(
        acquisitionTimeout: Duration,
        block: (R) -> T
    ): T {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        // Caution: The timeout is asynchronous to the executing code and can trigger at any point,
        //          even right before returning from inside the timeout block.
        // See: https://kotlinlang.org/docs/cancellation-and-timeouts.html#asynchronous-timeout-and-resources
        var poolable: P? = null
        try {
            withWorkContext(acquisitionTimeout) {
                @Suppress("UNCHECKED_CAST")
                poolable = performSelection().acquirePermit() as P
            }
            // The acquisition of the reference, and then a permit, was definitely successful.
            // Execution continues off the pool's thread, in the caller's context.
            return block(poolable!!.value)
        } finally {
            // The acquisition of the reference, and then a permit, may have failed. This reference
            // is null if and only if acquisition did indeed fail.
            //
            // This can happen if the acquisition was cancelled, perhaps the acquisition timeout
            // was met or a coroutine was otherwise cancelled out of band.
            poolable?.let {
                launchInWorkScope {
                    it.releasePermit()
                    if (it.isCanAcquire) {
                        onAvailable(it)
                    }
                }
            }
        }
    }

    @JvmSynthetic
    @PublishedApi
    internal abstract suspend fun performSelection(): Poolable<R>

    @JvmSynthetic
    @PublishedApi
    internal open fun onAvailable(poolable: P) = Unit

    @JvmSynthetic
    internal abstract fun allocatedSize(): Int

    @JvmSynthetic
    protected suspend fun assertThisDispatcher() {
        scopeGroup.assertThisDispatcher()
    }

    /**
     * Tests finding an available pooled object but without acquiring a permit.
     */
    @JvmSynthetic
    suspend fun testAcquisition(timeout: Duration) {
        withWorkContext(timeout) {
            performSelection()
        }
    }

    @JvmSynthetic
    open suspend fun prepare(): Int = 0

    @JvmSynthetic
    internal open suspend fun performClose() = Unit

    protected fun ensureActive() = scopeGroup.ensureActive()

    @JvmSynthetic
    protected suspend fun joinWork(): Unit = scopeGroup.joinWork()

    @JvmSynthetic
    protected fun <T> asyncInWorkScope(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = scopeGroup.asyncInWorkScope(start, block)

    @JvmSynthetic
    protected fun launchInMainScope(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job = scopeGroup.launchInMainScope(start, block)

    @JvmSynthetic
    @PublishedApi
    internal fun launchInWorkScope(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job = scopeGroup.launchInWorkScope(start, block)

    @JvmSynthetic
    internal suspend fun <T> withMainContext(
        block: suspend CoroutineScope.() -> T
    ): T = scopeGroup.withMainContext(block)

    @JvmSynthetic
    @PublishedApi
    internal suspend fun <T> withMainContext(
        timeout: Duration,
        block: suspend CoroutineScope.() -> T
    ): T = scopeGroup.withMainContext(timeout, block)

    @JvmSynthetic
    @PublishedApi
    internal suspend fun <T> withWorkContext(
        block: suspend CoroutineScope.() -> T
    ): T = scopeGroup.withWorkContext(block)

    @JvmSynthetic
    @PublishedApi
    internal suspend fun <T> withWorkContext(
        timeout: Duration,
        block: suspend CoroutineScope.() -> T
    ): T = scopeGroup.withWorkContext(timeout, block)

    @ThreadSafe
    inner class MetricsComponent {
        suspend fun gauges(timeout: Duration): Metrics = withMainContext(timeout) {
            Metrics(
                allocatedSize = this@SuspendPool.allocatedSize()
            )
        }
    }

    @ThreadSafe
    data class Metrics(
        val allocatedSize: Int
    )
}
