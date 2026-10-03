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

package com.bloomberg.pushiko.pools

import javax.annotation.concurrent.ThreadSafe
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@ThreadSafe
@Suppress("TooManyFunctions")
public sealed class SuspendPool<R : Any, P : Poolable<R>>(
    name: String = "Pushiko"
) {
    private val scopeGroup = SingleThreadScopeGroup(name)
    private var activeLeaseCount = 0
    private var activeLeasesDrained = CompletableDeferred(Unit)

    protected val isWorkActive: Boolean
        get() = scopeGroup.isWorkActive

    @JvmField
    public val metricsComponent: MetricsComponent = MetricsComponent()

    /**
     * Stops new pool work and waits for every active [withPermit] block to release its permit before shutdown.
     * Cancelling this call only stops the caller waiting; the pool-owned shutdown continues.
     * This must not be awaited from inside a [withPermit] block on the same pool because that lease cannot then finish.
     */
    @JvmSynthetic
    public suspend fun close() {
        scopeGroup.beginClose()
        performClose()
    }

    @JvmSynthetic
    public suspend inline fun <T : Any> withPermit(
        acquisitionTimeout: Duration,
        block: (R) -> T
    ): T {
        contract {
            callsInPlace(block, InvocationKind.AT_MOST_ONCE)
        }
        // Caution: The timeout is asynchronous to the executing code and can trigger at any point,
        //          even right before returning from inside the timeout block.
        // See: https://kotlinlang.org/docs/cancellation-and-timeouts.html#asynchronous-timeout-and-resources
        var poolable: P? = null
        var startNanos: Long? = null
        var failure: Throwable? = null
        try {
            withWorkContext(acquisitionTimeout) {
                @Suppress("UNCHECKED_CAST")
                poolable = performSelection().acquirePermit() as P
                registerLease()
            }
            // The acquisition of the reference, and then a permit, was definitely successful.
            // Execution continues off the pool's thread, in the caller's context.
            startNanos = System.nanoTime()
            return block(poolable!!.value)
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            // The acquisition of the reference, and then a permit, may have failed. This reference
            // is null if and only if acquisition did indeed fail.
            //
            // This can happen if the acquisition was canceled, perhaps the acquisition timeout
            // was met or a coroutine was otherwise canceled out of band.
            poolable?.let { acquired ->
                val cause = failure
                val holdNanos = startNanos?.let { System.nanoTime() - it }
                schedulePermitRelease(acquired, holdNanos, cause)
            }
        }
    }

    @JvmSynthetic
    @PublishedApi
    internal abstract suspend fun performSelection(): Poolable<R>

    @JvmSynthetic
    @PublishedApi
    internal open fun onAvailable(poolable: P): Unit = Unit

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
    public suspend fun testAcquisition(timeout: Duration) {
        withWorkContext(timeout) {
            performSelection()
        }
    }

    @JvmSynthetic
    public open suspend fun prepare(): Int = 0

    @JvmSynthetic
    internal open suspend fun performClose() = Unit

    @JvmSynthetic
    @PublishedApi
    internal fun registerLease() {
        if (activeLeaseCount++ == 0) {
            activeLeasesDrained = CompletableDeferred()
        }
    }

    private fun releaseLease() {
        if (--activeLeaseCount == 0) {
            activeLeasesDrained.complete(Unit)
        }
    }

    @JvmSynthetic
    @PublishedApi
    internal fun schedulePermitRelease(acquired: P, holdNanos: Long?, cause: Throwable?) {
        launchInMainScope {
            try {
                if (holdNanos != null) {
                    acquired.recordOutcome(holdNanos, cause == null || !acquired.isError(cause))
                }
            } finally {
                try {
                    acquired.releasePermit()
                } finally {
                    releaseLease()
                }
            }
            if (isWorkActive && (acquired.isCanAcquire || !acquired.isAlive)) {
                onAvailable(acquired)
            }
        }
    }

    @JvmSynthetic
    protected suspend fun joinActiveLeases(): Unit = activeLeasesDrained.await()

    @JvmSynthetic
    protected fun finishClose(): Unit = scopeGroup.finishClose()

    protected fun ensureActive(): Unit = scopeGroup.ensureActive()

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
    public inner class MetricsComponent {
        public suspend fun gauges(timeout: Duration): Metrics = withMainContext(timeout) {
            Metrics(
                allocatedSize = this@SuspendPool.allocatedSize()
            )
        }
    }

    @ThreadSafe
    public data class Metrics(
        val allocatedSize: Int
    )
}
