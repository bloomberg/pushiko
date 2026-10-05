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
import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.pushiko.commons.strings.commonPluralSuffix
import com.bloomberg.pushiko.pools.exceptions.PendingAcquisitionLimitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.annotations.VisibleForTesting
import java.io.StringWriter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashSet
import javax.annotation.concurrent.ThreadSafe
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * A non-blocking, lock-free pool that maintains a minimum number of pooled multiplexers even in the absence of
 * demand, heuristically scheduling the creation of new objects if need be and if the headroom exists to do so even
 * when there may be capacity available. When no further aggregate capacity is available further acquisition attempts
 * are treated as pending and their associated continuations buffer up over the pool, only resuming when capacity
 * becomes available again. When the pending-acquisition limit is reached, the arriving acquisition fails; existing
 * waiters retain their FIFO positions.
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
    private companion object {
        private const val MINIMUM_REAPER_BATCH_SIZE = 16
        private const val MAXIMUM_REAPER_BATCH_SIZE = 256
        private const val MAXIMUM_OWNED_SIZE_MULTIPLIER = 2
    }

    private val logger = Logger()

    private val pool = FifoBuffer<P>(capacity = configuration.maximumSize)
    private val retiredPoolables: MutableSet<P> = Collections.newSetFromMap(IdentityHashMap())

    private val pendingAcquisitions = LinkedHashSet<CancellableContinuation<Unit>>()
    private var pendingResumptionCount = 0
    private var pendingCreationCount = 0
    private var unrecycledSize = 0
    private val anticipatedSize: Int
        get() = pool.size + pendingCreationCount
    private val anticipatedOwnedSize: Int
        get() = anticipatedSize + retiredPoolables.size + unrecycledSize
    private val maximumOwnedSize = configuration.maximumSize * MAXIMUM_OWNED_SIZE_MULTIPLIER
    private val availableOwnedSlots: Int
        get() = (maximumOwnedSize - anticipatedOwnedSize).coerceAtLeast(0)

    private var scanLimitForPoolSize = -1
    private var cachedScanLimit = 0

    private val closeJob: Deferred<Unit> = asyncInMainScope(start = CoroutineStart.LAZY) {
        try {
            shutdown()
        } finally {
            finishClose()
        }
    }

    private var reaperJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val callbackDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val recyclingJobs = LinkedHashSet<Job>()

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
        doAttemptFill()
    }

    @JvmSynthetic
    override suspend fun performSelection(): P = acquirePoolable()

    override fun allocatedSize(): Int = pool.size + retiredPoolables.size + unrecycledSize

    @JvmSynthetic
    override fun onAvailable(poolable: P) {
        if (poolable.allocatedPermits == 0 && retiredPoolables.remove(poolable)) {
            scheduleDetachedRecycle(poolable)
        }
        if (poolable.isCanAcquire || !poolable.isAlive) {
            resumeNextPendingAcquisitions()
        }
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
                .appendLine("  Retired: ${retiredPoolables.size}")
                .appendLine("  Size: ${pool.size}")
                .appendLine("  Unrecycled: $unrecycledSize")
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
        closeJob.start()
        try {
            withContext(Dispatchers.Default) {
                withTimeout(configuration.shutdownTimeout) {
                    closeJob.await()
                }
            }
        } catch (exception: TimeoutCancellationException) {
            if (!closeJob.isCompleted) {
                logger.warn("Timed out waiting for pool {} to shut down; shutdown continues in the background", this)
            }
            throw exception
        }
    }

    private suspend fun acquirePoolable(): P {
        assertThisDispatcher()
        var wasResumed = false
        while (true) {
            try {
                ensureActive()
            } catch (exception: CancellationException) {
                if (wasResumed) {
                    --pendingResumptionCount
                    resumeForAvailableCapacity()
                }
                throw exception
            }
            if (wasResumed) {
                --pendingResumptionCount
            }
            selectPoolable()?.let {
                ensureMinimumAllocation()
                perhapsGrow(it)
                return it
            }
            awaitAvailability()
            wasResumed = true
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
            val poolable = pool.removeFirst()
            ++probed
            val canAcquire = poolable.isCanAcquire
            if (!poolable.isAlive) {
                retirePoolable(poolable)
                continue
            }
            pool.addLast(poolable)
            if (canAcquire) {
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
    internal suspend fun rescheduleReaperForTest(): Job? = withWorkContext {
        reaperJob.also { _ ->
            scheduleReaperJob()
        }
    }

    @JvmSynthetic
    @VisibleForTesting
    internal suspend fun isReaperScheduledForTest(): Boolean = withWorkContext { reaperJob != null }

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

    private fun retirePoolable(poolable: P) {
        poolable.clearAvailabilityChangedListener()
        if (poolable.allocatedPermits == 0) {
            scheduleDetachedRecycle(poolable)
        } else {
            retiredPoolables += poolable
        }
    }

    private fun scheduleRecycle(poolable: P): Job {
        poolable.clearAvailabilityChangedListener()
        return scheduleDetachedRecycle(poolable)
    }

    private fun scheduleDetachedRecycle(poolable: P): Job {
        check(poolable.allocatedPermits == 0) { "Cannot recycle a poolable with allocated permits" }
        recyclingJobs.removeAll(Job::isCompleted)
        ++unrecycledSize
        return launchInMainScope {
            runCatching {
                withContext(callbackDispatcher) {
                    recycler.recycle(poolable.value)
                }
            }.onFailure {
                logger.warn("Failed to recycle poolable", it)
            }
            --unrecycledSize
            val activeAcquisitionIsPending = pendingAcquisitions.any(CancellableContinuation<Unit>::isActive)
            if (isWorkActive && activeAcquisitionIsPending) {
                perhapsGrow(chosen = null)
            }
        }.also {
            recyclingJobs += it
        }
    }

    private fun P.isHealthy(): Boolean = currentErrorRate() <= configuration.errorRateThreshold

    private suspend fun awaitAvailability() {
        assertThisDispatcher()
        if (pendingAcquisitions.size >= configuration.maximumPendingAcquisitions) {
            pendingAcquisitions.removeAll { !it.isActive }
        }
        if (pendingAcquisitions.size >= configuration.maximumPendingAcquisitions) {
            throw PendingAcquisitionLimitException
        }
        suspendCancellableCoroutine { continuation ->
            pendingAcquisitions.add(continuation)
            continuation.invokeOnCancellation {
                launchInWorkScope {
                    pendingAcquisitions.remove(continuation)
                    resumeForAvailableCapacity()
                }
            }
            if (anticipatedSize < configuration.minimumSize) {
                launchInWorkScope(start = CoroutineStart.UNDISPATCHED) {
                    doAttemptFill()
                }
            } else {
                perhapsGrow(chosen = null)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun resumeNextPendingAcquisitions(limit: Int = 1) {
        repeat(limit) {
            val continuation = removeFirstActivePendingAcquisition() ?: return
            ++pendingResumptionCount
            continuation.resume(Unit) {
                launchInWorkScope {
                    --pendingResumptionCount
                    resumeForAvailableCapacity()
                }
            }
        }
    }

    private fun removeFirstActivePendingAcquisition(): CancellableContinuation<Unit>? {
        val iterator = pendingAcquisitions.iterator()
        while (iterator.hasNext()) {
            val continuation = iterator.next()
            iterator.remove()
            if (continuation.isActive) {
                return continuation
            }
        }
        return null
    }

    private fun resumeForAvailableCapacity() {
        val availablePermits = pool.fold(0L) { capacity, poolable ->
            capacity + if (poolable.isAlive && poolable.isCanAcquire) {
                (poolable.maximumPermits.toLong() - poolable.allocatedPermits).coerceAtLeast(1L)
            } else {
                0L
            }
        }
        val unnotifiedPermits = (availablePermits - pendingResumptionCount).coerceAtLeast(0L)
        resumeNextPendingAcquisitions(unnotifiedPermits.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun resumePendingAcquisitionWithException(
        continuation: CancellableContinuation<Unit>?,
        cause: Throwable
    ) {
        continuation?.let {
            runCatching {
                it.resumeWithException(cause)
            }.onFailure { e ->
                logger.debug("Exception failing a pending acquisition", e)
            }
        }
        if (pendingAcquisitions.any(CancellableContinuation<Unit>::isActive)) {
            // Dispatch the retry so that a synchronously failing factory cannot recurse through the entire queue.
            launchInWorkScope {
                if (pendingAcquisitions.any(CancellableContinuation<Unit>::isActive)) {
                    perhapsGrow(chosen = null)
                }
            }
        }
    }

    private fun failNextPendingAcquisition(cause: Throwable) {
        resumePendingAcquisitionWithException(removeFirstActivePendingAcquisition(), cause)
    }

    private fun propagateCreationFailure(cause: Throwable) {
        if (isWorkActive) {
            failNextPendingAcquisition(cause)
        }
    }

    private suspend fun ensureMinimumAllocation() {
        assertThisDispatcher()
        if (anticipatedSize < configuration.minimumSize) {
            launchInWorkScope(start = CoroutineStart.UNDISPATCHED) {
                doAttemptFill()
            }
        }
    }

    private suspend fun cleanPool() = withMainContext {
        pool.removeAll { poolable ->
            if (poolable.isAlive) {
                false
            } else {
                retirePoolable(poolable)
                true
            }
        }
    }

    private suspend fun doAttemptFill(): Int {
        assertThisDispatcher()
        cleanPool()
        val defect = minOf(configuration.minimumSize - anticipatedSize, availableOwnedSlots).also {
            if (it <= 0) {
                return 0
            }
        }
        logger.info("Attempting to create {} poolable{}", defect, defect.commonPluralSuffix())
        val results = coroutineScope {
            List(defect) {
                // Start without dispatching so that the pending counts remain coherent.
                async(start = CoroutineStart.UNDISPATCHED) {
                    tryCreatePoolable()
                }
            }.awaitAll()
        }
        results.mapNotNull(Result<P>::exceptionOrNull).forEach {
            logger.warn("Failure while creating a poolable", it)
        }
        val count = results.count(Result<P>::isSuccess)
        logger.info("Finished creating poolables: {} succeeded, {} failed", count, defect - count)
        return count
    }

    private suspend fun tryCreatePoolable(): Result<P> = try {
        Result.success(createPoolable())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun perhapsGrow(chosen: P?) {
        if (anticipatedSize >= configuration.maximumSize || availableOwnedSlots == 0) {
            return
        }
        when {
            anticipatedSize == 0 -> launchCreateExtra()
            chosen != null && !chosen.isHealthy() -> if (pendingCreationCount == 0) {
                logger.info("Creating poolable to replace unhealthy poolable")
                launchCreateExtra()
            }
            pendingCreationCount >= maxOf(configuration.minimumSize, pool.size) -> Unit
            chosen == null || !chosen.isShouldAcquire -> {
                logger.info("Creating poolable to relieve pressure")
                launchCreateExtra()
            }
            else -> Unit
        }
    }

    private suspend fun createPoolable(): P {
        assertThisDispatcher()
        ++pendingCreationCount
        var created: P? = null
        val result = try {
            try {
                Result.success(withContext(callbackDispatcher) {
                    factory.make().also { created = it }
                })
            } catch (exception: CancellationException) {
                created?.let {
                    withContext(NonCancellable) {
                        scheduleRecycle(it).join()
                    }
                }
                throw exception
            } catch (exception: Throwable) {
                Result.failure(exception)
            }
        } finally {
            --pendingCreationCount
        }
        val poolable = result.onFailure(::propagateCreationFailure).getOrThrow()
        val maximumPermits = poolable.maximumPermits
        if (maximumPermits < 0) {
            val exception = IllegalArgumentException(
                "Poolable maximum permits must be non-negative, got $maximumPermits"
            )
            recycleAndFailPendingAcquisition(poolable, exception)
        }
        return poolable.also {
            it.setAvailabilityChangedListener {
                launchInWorkScope {
                    retireIfUnavailable(it)
                    resumeForAvailableCapacity()
                }
            }
            rejectIfUnavailable(it)
            pool.addFirst(it)
            // Re-read dynamic capacity after installing the listener so that a concurrent increase cannot be lost.
            resumeForAvailableCapacity()
            if (pendingAcquisitions.any(CancellableContinuation<Unit>::isActive)) {
                perhapsGrow(chosen = null)
            }
        }
    }

    private suspend fun rejectIfUnavailable(poolable: P) {
        if (poolable.isAlive) {
            return
        }
        recycleAndFailPendingAcquisition(poolable, IllegalStateException("Factory returned an unavailable poolable"))
    }

    private suspend fun recycleAndFailPendingAcquisition(poolable: P, exception: Throwable): Nothing {
        val wasWorkActive = isWorkActive
        val pending = if (wasWorkActive) {
            removeFirstActivePendingAcquisition()
        } else {
            null
        }
        scheduleRecycle(poolable).join()
        if (wasWorkActive) {
            resumePendingAcquisitionWithException(pending, exception)
        }
        throw exception
    }

    private fun retireIfUnavailable(poolable: P) {
        if (poolable.isAlive) {
            return
        }
        var removed = false
        pool.removeAll {
            (it === poolable).also { matches ->
                if (matches) {
                    removed = true
                    retirePoolable(it)
                }
            }
        }
        if (removed && (
                anticipatedSize < configuration.minimumSize ||
                    pendingAcquisitions.any(CancellableContinuation<Unit>::isActive)
            )) {
            perhapsGrow(chosen = null)
        }
    }

    private suspend fun pruneIdlePoolables(): Boolean {
        assertThisDispatcher()
        cleanPool()
        currentCoroutineContext().ensureActive()
        var remainingInspections = pool.size
        val batchSize = sqrt(remainingInspections.toDouble()).toInt()
            .coerceIn(MINIMUM_REAPER_BATCH_SIZE, MAXIMUM_REAPER_BATCH_SIZE)
        var removed = 0
        while (remainingInspections > 0) {
            val maximum = (pool.size - configuration.minimumSize).coerceAtLeast(0)
            if (maximum == 0) {
                break
            }
            val inspections = minOf(batchSize, remainingInspections)
            val scheduledRecycling = ArrayList<Job>(inspections)
            removed += pool.removeAtMostFromLast(
                maximum,
                inspections,
                predicate = { it.allocatedPermits == 0 },
                onRemove = { scheduledRecycling += scheduleRecycle(it) }
            )
            scheduledRecycling.joinAll()
            remainingInspections -= inspections
            if (remainingInspections > 0) {
                yield()
            }
        }
        logger.info("Removed {} poolable{}", removed, removed.commonPluralSuffix())
        return pool.size > configuration.minimumSize
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
        reaperJob = launchInWorkScope {
            val job = currentCoroutineContext().job
            try {
                do {
                    delay(reaperDelay)
                } while (pruneIdlePoolables())
            } finally {
                if (reaperJob === job) {
                    reaperJob = null
                }
            }
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
        joinActiveLeases()
        reaperJob?.cancelAndJoin()
        recyclingJobs.toList().joinAll()
        recyclingJobs.clear()
        pool.forEach(Poolable<R>::clearAvailabilityChangedListener)
        try {
            factory.close()
        } catch (exception: Throwable) {
            logger.warn("Pool {} cleanup failed", this, exception)
            throw exception
        }
        logger.info("Pool {} has shutdown", this)
    }
}
