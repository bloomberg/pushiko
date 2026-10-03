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

package com.bloomberg.pushiko.pools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolHealthTest {
    private class HealthBandedPoolable(
        private val shouldAcquireLimit: Int,
        override val maximumPermits: Int,
        ewmaAlpha: Double = 0.9
    ) : Poolable<Any>(Any(), ewmaAlpha = ewmaAlpha, nanoTime = { 0L }) {
        override val isAlive: Boolean = true
        override val isCanAcquire: Boolean
            get() = allocatedPermits < maximumPermits
        override val isShouldAcquire: Boolean
            get() = allocatedPermits < shouldAcquireLimit

        fun degrade(failures: Int) = apply {
            repeat(failures) { recordOutcome(holdNanos = 0L, wasSuccess = false) }
        }
    }

    private class QueueFactory(
        poolables: List<Poolable<Any>>,
        private val beforeMake: suspend (Int) -> Unit = {}
    ) : Factory<Poolable<Any>>, Recycler<Any> {
        private val queue = ArrayDeque(poolables)
        private var _allocations = 0

        @Volatile
        var makeAttempts = 0
            private set

        override val allocations: Int
            get() = _allocations

        override suspend fun close() = Unit

        override suspend fun make(): Poolable<Any> {
            beforeMake(++makeAttempts)
            ++_allocations
            return queue.removeFirst()
        }

        override fun recycle(obj: Any) {
            --_allocations
        }
    }

    private fun newPool(
        factory: QueueFactory,
        size: Int,
        maximumSize: Int = size,
        errorRateThreshold: Double = 0.5
    ) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumPendingAcquisitions = 1_000,
            maximumSize = maximumSize,
            minimumSize = size,
            reaperDelay = 10L.minutes,
            summaryInterval = 5L.minutes,
            errorRateThreshold = errorRateThreshold,
            fullScanPoolSize = 10,
            maximumSampledScan = 20
        ),
        factory,
        factory
    )

    @Test
    fun prefersHealthyShouldAcquireOverHealthyFallback() = runTest {
        val healthyFallback = HealthBandedPoolable(shouldAcquireLimit = 0, maximumPermits = 10)
        val healthyPreferred = HealthBandedPoolable(shouldAcquireLimit = 10, maximumPermits = 10)
        val factory = QueueFactory(listOf(healthyFallback, healthyPreferred))
        val pool = newPool(factory, size = 2)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertSame(healthyPreferred, pool.selectPoolableForTest())
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun prefersHealthyFallbackOverUnhealthyShouldAcquire() = runTest {
        val unhealthyPreferred = HealthBandedPoolable(shouldAcquireLimit = 10, maximumPermits = 10).degrade(2)
        val healthyFallback = HealthBandedPoolable(shouldAcquireLimit = 0, maximumPermits = 10)
        val factory = QueueFactory(listOf(unhealthyPreferred, healthyFallback))
        val pool = newPool(factory, size = 2)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertSame(healthyFallback, pool.selectPoolableForTest())
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun returnsUnhealthyLastResortWhenNoHealthyPoolable() = runTest {
        val first = HealthBandedPoolable(shouldAcquireLimit = 10, maximumPermits = 10).degrade(2)
        val second = HealthBandedPoolable(shouldAcquireLimit = 10, maximumPermits = 10).degrade(2)
        val factory = QueueFactory(listOf(first, second))
        val pool = newPool(factory, size = 2)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                val selected = pool.selectPoolableForTest()
                assertNotNull(selected)
                assert(selected.currentErrorRate() > 0.5) {
                    "Expected an unhealthy last-resort poolable, error rate was ${selected.currentErrorRate()}"
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun unhealthyLastResortTriggersReplacementGrowth() = runTest {
        val unhealthy = HealthBandedPoolable(shouldAcquireLimit = 10, maximumPermits = 10).degrade(2)
        val replacement = HealthBandedPoolable(shouldAcquireLimit = 10, maximumPermits = 10)
        val factory = QueueFactory(listOf(unhealthy, replacement))
        val pool = newPool(factory, size = 1, maximumSize = 2)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()

                pool.withPermit(5L.seconds) {
                    assertSame(unhealthy.value, it)
                }

                withTimeout(5L.seconds) {
                    while (factory.allocations != 2) {
                        yield()
                    }
                }
                assertEquals(2, factory.allocations)
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun unhealthyLastResortStartsAtMostOneReplacementAtATime() = runTest {
        val unhealthy = List(3) {
            HealthBandedPoolable(shouldAcquireLimit = 10, maximumPermits = 10).degrade(2)
        }
        val replacementStarted = CompletableDeferred<Unit>()
        val allowReplacement = CompletableDeferred<Unit>()
        val factory = QueueFactory(
            unhealthy + List(3) {
                HealthBandedPoolable(shouldAcquireLimit = 10, maximumPermits = 10)
            }
        ) { attempt ->
            if (attempt == 4) {
                replacementStarted.complete(Unit)
                allowReplacement.await()
            }
        }
        val pool = newPool(factory, size = 3, maximumSize = 6)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()

                pool.testAcquisition(5L.seconds)
                withTimeout(5L.seconds) {
                    replacementStarted.await()
                }

                repeat(5) {
                    pool.testAcquisition(5L.seconds)
                }
                assertEquals(4, factory.makeAttempts)

                allowReplacement.complete(Unit)
                withTimeout(5L.seconds) {
                    while (factory.allocations != 4) {
                        yield()
                    }
                }
            }
        } finally {
            allowReplacement.complete(Unit)
            pool.close()
        }
    }

    @Test
    fun errorRateAtThresholdIsTreatedAsHealthy() = runTest {
        val atThreshold = HealthBandedPoolable(
            shouldAcquireLimit = 10,
            maximumPermits = 10,
            ewmaAlpha = 0.5
        ).degrade(1)
        val aboveThreshold = HealthBandedPoolable(
            shouldAcquireLimit = 10,
            maximumPermits = 10,
            ewmaAlpha = 0.5
        ).degrade(2)
        val factory = QueueFactory(listOf(aboveThreshold, atThreshold))
        val pool = newPool(factory, size = 2, errorRateThreshold = 0.5)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertSame(atThreshold, pool.selectPoolableForTest())
            }
        } finally {
            pool.close()
        }
    }
}
