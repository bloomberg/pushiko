/*
 * Copyright 2026 Bloomberg Finance L.P.
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

@file:Suppress("MagicNumber")

package com.bloomberg.pushiko.pools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.RepeatedTest
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolChaosTest {
    @RepeatedTest(TRIAL_COUNT)
    fun flappingEligibilityNeverExceedsFastFailBound() = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(TRIAL_BUDGET) {
                runFlapOnlyTrial()
            }
        }
    }

    @RepeatedTest(TRIAL_COUNT)
    fun chaosNeverLeaksTheOwnedResourceBudget() = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(TRIAL_BUDGET) {
                runResourceChaosTrial()
            }
        }
    }

    private suspend fun runFlapOnlyTrial() {
        val random = ThreadLocalRandom.current()
        val config = ChaosConfig(
            deathRate = 0.0,
            drainRate = 0.0,
            flapRate = 1.0,
            makeFailureRate = 0.0,
            makeHangRate = 0.0,
            recycleFailureRate = 0.0
        )
        withPoolUnderChaos(random, config) { pool ->
            coroutineScope {
                List(random.nextInt(MINIMUM_WORKERS, MAXIMUM_WORKERS + 1)) {
                    async { runFastFailWorker(pool) }
                }.awaitAll()
            }
        }
    }

    private suspend fun runResourceChaosTrial() {
        val random = ThreadLocalRandom.current()
        val config = ChaosConfig(
            deathRate = random.nextDouble(0.0, MAXIMUM_DEATH_RATE),
            drainRate = random.nextDouble(0.0, MAXIMUM_DRAIN_RATE),
            flapRate = 0.0,
            makeFailureRate = random.nextDouble(0.0, MAXIMUM_MAKE_FAILURE_RATE),
            makeHangRate = random.nextDouble(0.0, MAXIMUM_MAKE_HANG_RATE),
            recycleFailureRate = random.nextDouble(0.0, MAXIMUM_RECYCLE_FAILURE_RATE)
        )
        withPoolUnderChaos(random, config) { pool ->
            coroutineScope {
                List(random.nextInt(MINIMUM_WORKERS, MAXIMUM_WORKERS + 1)) {
                    async { runResourceChaosWorker(pool) }
                }.awaitAll()
            }
            withTimeout(CANARY_BOUND) {
                var succeeded = false
                while (!succeeded) {
                    try {
                        pool.withPermit(Duration.INFINITE) { Unit }
                        succeeded = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { }
                }
            }
        }
    }

    private suspend fun withPoolUnderChaos(
        random: ThreadLocalRandom,
        config: ChaosConfig,
        block: suspend (CommonMuxPool<Any, ChaosPoolable>) -> Unit
    ) {
        val factory = ChaosFactory(config)
        val pool = CommonMuxPool(
            configuration = poolConfiguration(
                maximumPendingAcquisitions = 200,
                maximumSize = random.nextInt(MINIMUM_POOL_SIZE, MAXIMUM_POOL_SIZE + 1),
                minimumSize = 1,
                reaperDelay = Duration.INFINITE,
                summaryInterval = Duration.INFINITE
            ),
            factory,
            factory
        )
        try {
            withTimeout(PREPARE_BOUND) {
                pool.prepare()
            }
            block(pool)
        } finally {
            withTimeout(CLOSE_BOUND) {
                pool.close()
            }
        }
    }

    private suspend fun runFastFailWorker(pool: CommonMuxPool<Any, ChaosPoolable>) {
        repeat(OPERATIONS_PER_WORKER) {
            val completed = withTimeoutOrNull(FAST_FAIL_BOUND) {
                try {
                    pool.withPermit(Duration.INFINITE) { }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
            }
            assertTrue(
                completed != null,
                "A permit acquisition neither succeeded nor failed within $FAST_FAIL_BOUND"
            )
        }
    }

    private suspend fun runResourceChaosWorker(pool: CommonMuxPool<Any, ChaosPoolable>) {
        repeat(OPERATIONS_PER_WORKER) {
            try {
                pool.withPermit(WORKER_ACQUISITION_TIMEOUT) {
                    if (ThreadLocalRandom.current().nextDouble() < WORK_FAILURE_RATE) {
                        throw IOException("Chaos: injected request failure")
                    }
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (e !is TimeoutCancellationException) {
                    throw e
                }
            } catch (_: Exception) { }
        }
    }

    private companion object {
        private const val TRIAL_COUNT = 20
        private const val MINIMUM_POOL_SIZE = 1
        private const val MAXIMUM_POOL_SIZE = 3
        private const val MINIMUM_WORKERS = 2
        private const val MAXIMUM_WORKERS = 5
        private const val OPERATIONS_PER_WORKER = 10
        private const val WORK_FAILURE_RATE = 0.1
        private const val MAXIMUM_DEATH_RATE = 0.15
        private const val MAXIMUM_DRAIN_RATE = 0.15
        private const val MAXIMUM_MAKE_FAILURE_RATE = 0.15
        private const val MAXIMUM_MAKE_HANG_RATE = 0.1
        private const val MAXIMUM_RECYCLE_FAILURE_RATE = 0.4
        private val PREPARE_BOUND = 5L.seconds
        private val FAST_FAIL_BOUND = 2L.seconds
        private val WORKER_ACQUISITION_TIMEOUT = 2L.seconds
        private val CANARY_BOUND = 10L.seconds
        private val CLOSE_BOUND = 10L.seconds
        private val TRIAL_BUDGET = 60L.seconds
    }
}

private data class ChaosConfig(
    val deathRate: Double,
    val drainRate: Double,
    val flapRate: Double,
    val makeFailureRate: Double,
    val makeHangRate: Double,
    val recycleFailureRate: Double
)

private class ChaosPoolable(config: ChaosConfig) : Poolable<Any>(Any()) {
    private val isFlapper = ThreadLocalRandom.current().nextDouble() < config.flapRate
    private val deathRate = config.deathRate
    private val drainRate = config.drainRate
    private var flapReads = 0

    @Volatile
    private var dead = false

    @Volatile
    private var draining = false

    override val maximumPermits = 1

    override val isAlive: Boolean
        get() = !dead

    override val isDraining: Boolean
        get() = draining

    override val isCanAcquire: Boolean
        get() {
            rollDeathAndDrain()
            return when {
                dead || draining -> false
                isFlapper -> (flapReads++ % 2 == 0).also { available ->
                    if (available) {
                        notifyAvailabilityChanged()
                    }
                }
                else -> allocatedPermits < maximumPermits
            }
        }

    override val isShouldAcquire: Boolean
        get() = isCanAcquire

    private fun rollDeathAndDrain() {
        val random = ThreadLocalRandom.current()
        if (!dead && random.nextDouble() < deathRate) {
            dead = true
            notifyAvailabilityChanged()
        }
        if (!draining && random.nextDouble() < drainRate) {
            draining = true
            notifyAvailabilityChanged()
        }
    }
}

private class ChaosFactory(private val config: ChaosConfig) : Factory<ChaosPoolable>, Recycler<Any> {
    private val madeCount = AtomicInteger()
    private val recycledCount = AtomicInteger()

    override val allocations: Int
        get() = madeCount.get() - recycledCount.get()

    override suspend fun close() = Unit

    override suspend fun make(): ChaosPoolable {
        val random = ThreadLocalRandom.current()
        if (random.nextDouble() < config.makeHangRate) {
            delay(random.nextLong(1L, MAXIMUM_MAKE_HANG_MILLIS).milliseconds)
        }
        if (random.nextDouble() < config.makeFailureRate) {
            throw IOException("Chaos: injected creation failure")
        }
        madeCount.incrementAndGet()
        return ChaosPoolable(config)
    }

    override fun recycle(obj: Any) {
        recycledCount.incrementAndGet()
        if (ThreadLocalRandom.current().nextDouble() < config.recycleFailureRate) {
            error("Chaos: injected recycle failure")
        }
    }

    private companion object {
        private const val MAXIMUM_MAKE_HANG_MILLIS = 50L
    }
}
