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

package com.bloomberg.pushiko.pools

import com.bloomberg.pushiko.commons.FifoBuffer
import com.bloomberg.pushiko.commons.removeUntil
import com.bloomberg.pushiko.commons.removeUntilInclusiveOrNull
import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.pushiko.commons.strings.commonPluralSuffix
import com.bloomberg.pushiko.pools.exceptions.PendingAcquisitionLimitException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.annotations.VisibleForTesting
import java.io.StringWriter
import java.util.LinkedList
import javax.annotation.concurrent.ThreadSafe
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * A non-blocking, lock-free pool that maintains a minimum number of pooled multiplexers even in the absence of
 * demand, heuristically scheduling the creation of new objects if need be and if the headroom exists to do so even
 * when there may be capacity available. When no further aggregate capacity is available further acquisition attempts
 * are treated as pending and their associated continuations buffer up over the pool, only resuming when capacity
 * becomes available again. If there are too many queued pending acquisitions the pool begins failing the oldest of
 * these by resuming the associated continuation with an exception.
 *
 * The acquisition, creation (or not) and release of objects - in other words, the state of the pool - is entirely
 * confined to the pool and orchestrated from a coroutine dispatcher backed exclusively by a single dedicated thread.
 * Intensive or blocking work on this thread would stop the world and is always avoided. Once acquired, the use of a
 * borrowed object is offloaded at the earliest opportunity and the object is released immediately after its
 * acquisition for further acquisition without awaiting the conclusion of the work. The work using a borrowed object
 * may fail but always does so in isolation without affecting another.
 *
 * The closing of the pool is orderly and is triggered by calling [close] which cancels the control job underpinning
 * the pool's work. Once this is initiated subsequent acquisition attempts will fail and promptly meet with an
 * exception indicating that the pool is closed. All queued pending acquisition attempts will also fail, their
 * associated continuations resuming with an exception.
 */
@ThreadSafe
public class CommonMuxPool<R : Any, P : Poolable<R>>(
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

    private var scanLimitForPoolSize = -1
    private var cachedScanLimit = 0

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
    @VisibleForTesting
    internal suspend fun pendingAcquisitionCount(): Int = withWorkContext { pendingAcquisitions.size }

    @JvmSynthetic
    private suspend fun summarize() = withMainContext {
        val writer = StringWriter().apply {
            appendLine("Pool $this:")
                .appendLine("  Allocations: ${factory.allocations}")
                .appendLine("  Maximum pending acquisitions: ${configuration.maximumPendingAcquisitions}")
                .appendLine("  Maximum size: ${configuration.maximumSize}")
                .appendLine("  Minimum size: ${configuration.minimumSize}")
                .appendLine("  Pending acquisitions: ${pendingAcquisitions.size}")
                .appendLine("  Pending creations: $pendingCreationCount")
                .appendLine("  Probe limit: ${probeLimit()}")
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

    private tailrec suspend fun acquirePoolable(): P {
        assertThisDispatcher()
        ensureActive()
        ensureMinimumAllocation()
        val chosen = selectPoolable()
        return if (chosen != null) {
            perhapsGrow(chosen)
            chosen
        } else {
            awaitAvailability()
            acquirePoolable()
        }
    }

    @JvmSynthetic
    @VisibleForTesting
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    internal fun selectPoolable(): P? {
        var fallback: P? = null
        var lastResort: P? = null
        var probed = 0
        while (pool.isNotEmpty() && probed < probeLimit()) {
            val poolable = rotateNextAlive() ?: continue
            ++probed
            if (poolable.isCanAcquire) {
                when {
                    poolable.isShouldAcquire && poolable.isHealthy() -> return poolable
                    poolable.isHealthy() -> if (fallback == null) { fallback = poolable }
                    else -> if (lastResort == null) { lastResort = poolable }
                }
            }
        }
        return fallback ?: lastResort
    }

    @JvmSynthetic
    @VisibleForTesting
    internal suspend fun selectPoolableForTest(): P? = withWorkContext { selectPoolable() }

    @JvmSynthetic
    @VisibleForTesting
    internal suspend fun pendingAcquisitionCountForTest(): Int = withWorkContext { pendingAcquisitions.size }

    private fun probeLimit(): Int = if (anticipatedSize >= configuration.maximumSize) {
        pool.size
    } else {
        scanLimit()
    }

    private fun scanLimit(): Int {
        val poolSize = pool.size
        if (poolSize != scanLimitForPoolSize) {
            scanLimitForPoolSize = poolSize
            cachedScanLimit = computeScanLimit(poolSize)
        }
        return cachedScanLimit
    }

    private fun computeScanLimit(poolSize: Int): Int = when {
        poolSize <= configuration.fullScanPoolSize -> poolSize
        else -> (configuration.fullScanPoolSize +
            ceil(sqrt((poolSize - configuration.fullScanPoolSize).toDouble())).toInt())
            .coerceAtMost(configuration.maximumSampledScan)
    }

    private fun rotateNextAlive(): P? {
        val poolable = pool.removeFirst()
        return if (poolable.isAlive) {
            pool.addLast(poolable)
            poolable
        } else {
            null
        }
    }

    private fun P.isHealthy(): Boolean = currentErrorRate() <= configuration.errorRateThreshold

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
            } else {
                perhapsGrow(chosen = null)
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

    private fun perhapsGrow(chosen: P?) {
        if (anticipatedSize >= configuration.maximumSize) {
            return
        }
        when {
            anticipatedSize == 0 -> launchCreateExtra()
            pendingCreationCount >= maxOf(configuration.minimumSize, pool.size) -> Unit
            chosen == null || !chosen.isShouldAcquire -> {
                logger.info("Creating poolable to relieve pressure")
                launchCreateExtra()
            }
            else -> Unit
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
