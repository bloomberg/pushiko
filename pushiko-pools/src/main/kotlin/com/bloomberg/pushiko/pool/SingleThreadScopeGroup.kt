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

import com.bloomberg.pushiko.commons.assertions.assert
import com.bloomberg.pushiko.pool.exceptions.PoolClosedException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.annotation.concurrent.ThreadSafe
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

@ThreadSafe
class SingleThreadScopeGroup(
    name: String
) : AutoCloseable {
    private val dispatcher = SingleThreadExecutorService(name).asCoroutineDispatcher()

    private val mainJob = SupervisorJob()
    private val workJob = SupervisorJob(mainJob)

    private val mainContext: CoroutineContext = dispatcher + mainJob
    private val workContext: CoroutineContext = dispatcher + workJob

    private val mainScope = CoroutineScope(mainContext)
    private val workScope = CoroutineScope(workContext)

    /**
     * Closes resources in an orderly manner.
     */
    override fun close() {
        workJob.cancel(PoolClosedException)
        mainJob.run {
            complete()
            // Add this handler at the last minute to allow every opportunity for other components
            // to register their handlers first.
            invokeOnCompletion {
                dispatcher.close()
            }
        }
    }

    @get:JvmSynthetic
    internal val isWorkActive: Boolean
        get() = workContext.isActive

    @JvmSynthetic
    internal fun ensureActive() = workContext.ensureActive()

    @JvmSynthetic
    internal suspend fun joinWork(): Unit = workJob.join()

    @JvmSynthetic
    internal suspend fun assertThisDispatcher() {
        assert("Execution must be confined to the single-threaded CoroutineDispatcher of this group") {
            coroutineContext[ContinuationInterceptor] === dispatcher
        }
    }

    @JvmSynthetic
    internal fun <T> asyncInWorkScope(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = workScope.async(EmptyCoroutineContext, start, block)

    @JvmSynthetic
    internal fun launchInMainScope(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job = mainScope.launch(EmptyCoroutineContext, start, block)

    @JvmSynthetic
    internal fun launchInWorkScope(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job = workScope.launch(EmptyCoroutineContext, start, block)

    @JvmSynthetic
    internal suspend fun <T> withMainContext(
        block: suspend CoroutineScope.() -> T
    ): T = withContext(mainContext, block)

    /**
     * Invokes the passed block with a given timeout in a scope whose parent is [mainJob], suspends until it completes,
     * and then returns the result. The timeout covers the context switching to and from the dispatcher.
     */
    @JvmSynthetic
    internal suspend fun <T> withMainContext(
        timeout: Duration,
        block: suspend CoroutineScope.() -> T
    ): T = withContext(mainJob) {
        withTimeout(timeout) {
            withContext(dispatcher, block)
        }
    }

    @JvmSynthetic
    internal suspend fun <T> withWorkContext(
        block: suspend CoroutineScope.() -> T
    ): T = withContext(workContext, block)

    /**
     * Invokes the passed block with a given timeout in a scope whose parent is [workJob], suspends until it completes,
     * and then returns the result. The timeout covers the context switching to and from the dispatcher.
     */
    @JvmSynthetic
    internal suspend fun <T> withWorkContext(
        timeout: Duration,
        block: suspend CoroutineScope.() -> T
    ): T = withContext(workJob) {
        withTimeout(timeout) {
            withContext(dispatcher, block)
        }
    }
}
