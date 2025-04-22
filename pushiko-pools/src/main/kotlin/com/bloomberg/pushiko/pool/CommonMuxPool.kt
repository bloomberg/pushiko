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

package com.bloomberg.pushiko.pool

import com.bloomberg.pushiko.commons.FifoBuffer
import com.bloomberg.pushiko.commons.removeUntil
import com.bloomberg.pushiko.commons.removeUntilInclusiveOrNull
import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.pushiko.commons.strings.commonPluralSuffix
import com.bloomberg.pushiko.pool.exceptions.PendingAcquisitionLimitException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.StringWriter
import java.util.LinkedList
import javax.annotation.concurrent.ThreadSafe
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A non-blocking, lock-free pool that maintains a minimum number of pooled multiplexers even in the absence of
 * demand, heuristically scheduling the creation of new objects if need be and if the headroom exists to do so even
 * when there may be capacity available elsewhere. When no further aggregate capacity is available further acquisition
 * attempts are treated as pending and their associated continuations buffer up over the pool, only resuming when
 * capacity becomes available again. If there are too many queued pending acquisitions the pool begins failing the
 * oldest of these by resuming the associated continuation with an exception.
 *
 * The acquisition, creation (or not) and release of objects - in other words, the state of the pool - is entirely
 * confined to the pool and orchestrated from a coroutine dispatcher backed exclusively by a single dedicated thread.
 * Intensive or blocking work on this thread would stop the world and is always avoided. Once acquired, the use of a
 * borrowed object is offloaded at the earliest opportunity and the object is release immediately after its
 * acquisition for further acquisition without awaiting the conclusion of the work. The work using a borrowed object
 * may fail but always do so in isolation without affecting another.
 *
 * The closing of the pool is orderly and is triggered by calling [close] which cancels the control job underpinning
 * the pool's work. Once this is initiated subsequent acquisition attempts will fail and promptly meet with an
 * exception indicating that the pool is closed. All queued pending acquisition attempts will also fail, their
 * associated continuations resuming with an exception.
 */
@ThreadSafe
class CommonMuxPool<R : Any, P : Poolable<R>>(
    private val configuration: PoolConfiguration,
    private val factory: Factory<P>,
    private val recycler: Recycler<R>
) : SuspendPool<R, P>(configuration.name) {
    private val logger = Logger()

    private val pool = FifoBuffer<P>(capacity = configuration.maximumSize)

    private val pendingAcquisitions = LinkedList<CancellableContinuation<Unit>>()
    private var pendingCreationCount = 0
    private val anticipatedSize: Int
        get() = pool.size + pendingCreationCount

    private val closeJob = launchInMainScope(start = CoroutineStart.LAZY) {
        shutdown()
    }

    private var reaperJob: Job? = null

    init {
        configuration.summaryInterval.takeIf { it.isPositive() && it.isFinite() }?.let {
            launchInWorkScope {
                while (isActive) {
                    delay(it)
                    summarize()
                }
            }
        }
    }

    @JvmSynthetic
    override suspend fun prepare(): Int = withWorkContext {
        attemptFill()
    }

    @JvmSynthetic
    override suspend fun performSelection(): P = acquirePoolable()

    override fun allocatedSize(): Int = pool.size

    @JvmSynthetic
    override fun onAvailable(poolable: P) {
        resumeNextPendingAcquisitions()
    }

    @JvmSynthetic
    private suspend fun summarize() = withMainContext {
        val writer = StringWriter().apply {
            appendLine("Pool $this:")
                .appendLine("  Acquisition attempts threshold: ${configuration.acquisitionAttemptsThreshold}")
                .appendLine("  Allocations: ${factory.allocations}")
                .appendLine("  Maximum pending acquisitions: ${configuration.maximumPendingAcquisitions}")
                .appendLine("  Maximum size: ${configuration.maximumSize}")
                .appendLine("  Minimum size: ${configuration.minimumSize}")
                .appendLine("  Pending acquisitions: ${pendingAcquisitions.size}")
                .appendLine("  Pending creations: $pendingCreationCount")
                .appendLine("  Size: ${pool.size}")
        }
        pool.map {
            launchInWorkScope {
                it.summarize(writer)
            }
        }.joinAll()
        logger.info(writer.toString())
    }

    @JvmSynthetic
    override suspend fun performClose() {
        closeJob.join()
    }

    private tailrec suspend fun acquirePoolable(attempts: Int = 1): P {
        assertThisDispatcher()
        ensureActive()
        ensureMinimumAllocation()
        pool.removeUntilFirstInclusiveOrNull { it.isAlive }?.let {
            // Add the poolable to the back of the queue immediately, ready for another acquirer,
            // now that we have a reference to inspect.
            pool.addLast(it)
            if (poolablePredicate(it, attempts)) {
                return it
            }
        }
        return if (attempts >= pool.size) {
            // Each poolable has been visited and rejected at least once.
            awaitAvailability()
            acquirePoolable(1)
        } else {
            acquirePoolable(attempts + 1)
        }
    }

    private suspend fun awaitAvailability() {
        assertThisDispatcher()
        pendingAcquisitions.removeUntil { it.isActive }
        if (pendingAcquisitions.size >= configuration.maximumPendingAcquisitions) {
            // The pending acquisitions queue is full, clear a slot.
            runCatching {
                pendingAcquisitions.poll()?.resumeWithException(PendingAcquisitionLimitException)
            }.onFailure {
                logger.debug("Exception resuming a pending acquisition", it)
            }
        }
        suspendCancellableCoroutine { continuation ->
            pendingAcquisitions.add(continuation)
            continuation.invokeOnCancellation {
                launchInWorkScope {
                    pendingAcquisitions.removeUntil { it.isActive }
                }
            }
            if (anticipatedSize < configuration.minimumSize) {
                launchInWorkScope(start = CoroutineStart.UNDISPATCHED) {
                    attemptFill()
                }
            } else if (configuration.minimumSize == 0 && anticipatedSize == 0) {
                launchCreateExtra()
            }
        }
    }

    private fun resumeNextPendingAcquisitions(limit: Int = 1) {
        repeat(limit) {
            if (pendingAcquisitions.isEmpty()) {
                return
            }
            pendingAcquisitions.removeUntilInclusiveOrNull { it.isActive }?.resume(Unit)
        }
    }

    private suspend fun attemptFill(): Int = withWorkContext {
        doAttemptFill()
    }

    private suspend fun ensureMinimumAllocation() {
        assertThisDispatcher()
        if (anticipatedSize < configuration.minimumSize) {
            launchInWorkScope(start = CoroutineStart.UNDISPATCHED) {
                attemptFill()
            }
        }
    }

    private suspend fun cleanPool() = withMainContext {
        pool.removeAll { !it.isAlive }
    }

    private suspend fun doAttemptFill(): Int {
        assertThisDispatcher()
        cleanPool()
        val defect = (configuration.minimumSize - anticipatedSize).also {
            if (it <= 0) {
                return 0
            }
        }
        logger.info("Attempting to create {} poolable{}", defect, defect.commonPluralSuffix())
        var count = 0
        runCatching {
            List(defect) {
                // Start without dispatching so that the pending counts remain coherent.
                asyncInWorkScope(start = CoroutineStart.UNDISPATCHED) {
                    createPoolable()
                    ++count
                }
            }.awaitAll()
        }.onFailure {
            logger.warn("Failure while creating a poolable, " +
                "created $count poolable${defect.commonPluralSuffix()}", it)
        }.onSuccess {
            logger.info("Successfully added {} poolable{} to the pool", count, count.commonPluralSuffix())
        }
        return count
    }

    private val poolablePredicate: (Poolable<R>, Int) -> Boolean = { poolable, attempts ->
        if (poolable.isShouldAcquire) {
            true
        } else if (
            pendingCreationCount >= maxOf(configuration.minimumSize, pool.size) &&
            poolable.isCanAcquire
        ) {
            // The poolable has some availability but there are also more poolables due to be
            // created. Creating even more now may not help to relieve the pressure any sooner.
            true
        } else if (anticipatedSize < configuration.maximumSize) {
            // The poolable is busy, and we should try not to acquire it.
            if (attempts >= minOf((pool.size + 1) / 2, configuration.acquisitionAttemptsThreshold)) {
                // At least half of the available poolables have by now been tested and rejected.
                // As such, "enough" attempts to acquire a poolable have failed. If the pool is not
                // about to at least double in size then prepare a new poolable before returning.
                if (pendingCreationCount < maxOf(configuration.minimumSize, pool.size)) {
                    // Relieve the pressure by scheduling the creation of a new poolable.
                    logger.info("Creating poolable after {} acquisition attempt{}", attempts, attempts.commonPluralSuffix())
                    launchCreateExtra()
                }
                true
            } else {
                false
            }
        } else if (poolable.isCanAcquire) {
            // There's no further capacity to create new poolables but as a last resort at least
            // this one can be acquired.
            true
        } else {
            logger.warn("Distressed poolable: {} permits: {}", poolable, poolable.allocatedPermits)
            false
        }
    }

    private suspend fun createPoolable(): P = withWorkContext {
        ++pendingCreationCount
        try {
            factory.make()
        } finally {
            --pendingCreationCount
        }.also {
            pool.addFirst(it)
            resumeNextPendingAcquisitions(it.maximumPermits)
        }
    }

    // TODO Smarter heuristic?
    private suspend fun prunePool() = withWorkContext {
        cleanPool()
        val initialSize = pool.size
        while (pool.size > configuration.minimumSize) {
            recycler.recycle(pool.removeLast().value)
        }
        val difference = initialSize - pool.size
        logger.info("Removed {} poolable{}", difference, difference.commonPluralSuffix())
    }

    private fun launchCreateExtra() {
        // Start without dispatching so that the pending counts remain coherent.
        launchInWorkScope(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                createPoolable()
            }.onFailure {
                logger.warn("Failed to create extra", it)
            }
            scheduleReaperJob()
        }
    }

    private fun scheduleReaperJob() {
        val reaperDelay = configuration.reaperDelay.takeIf { it.isPositive() && it.isFinite() } ?: return
        reaperJob?.cancel()
        reaperJob = launchInMainScope {
            delay(reaperDelay)
            prunePool()
            reaperJob = null
        }
    }

    private suspend fun shutdown() {
        assertThisDispatcher()
        logger.info("Pool {} has {} pending creation{}", this, pendingCreationCount,
            pendingCreationCount.commonPluralSuffix())
        logger.info("Pool {} has {} pending acquisition{}", this, pendingAcquisitions.size,
            pendingAcquisitions.size.commonPluralSuffix())
        assert(!isWorkActive) { "Pool must already be closed by cancelling the worker job" }
        pendingAcquisitions.clear()
        joinWork()
        factory.close()
        logger.info("Pool {} has shutdown", this)
    }
}
