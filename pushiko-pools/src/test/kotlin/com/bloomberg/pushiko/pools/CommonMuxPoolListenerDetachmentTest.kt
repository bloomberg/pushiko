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
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommonMuxPoolListenerDetachmentTest {
    private class Resource

    private class ListenerTrackingPoolable(resource: Resource) : Poolable<Resource>(resource) {
        override val maximumPermits: Int = 1
        override var isAlive: Boolean = true
        override val isCanAcquire: Boolean
            get() = isAlive && allocatedPermits < maximumPermits
        override val isShouldAcquire: Boolean
            get() = isCanAcquire

        var isListenerAttached = false
            private set

        override fun onAvailabilityChangedListenerChanged(isAttached: Boolean) {
            isListenerAttached = isAttached
        }
    }

    private class TrackingFactory : Factory<ListenerTrackingPoolable>, Recycler<Resource> {
        val poolable = ListenerTrackingPoolable(Resource())
        val recycled = CompletableDeferred<Unit>()
        var listenerAttachedAtRecycle: Boolean? = null
        var listenerAttachedAtClose: Boolean? = null

        override val allocations: Int
            get() = if (recycled.isCompleted) { 0 } else { 1 }

        override suspend fun make() = poolable

        override fun recycle(obj: Resource) {
            listenerAttachedAtRecycle = poolable.isListenerAttached
            recycled.complete(Unit)
        }

        override suspend fun close() {
            listenerAttachedAtClose = poolable.isListenerAttached
        }
    }

    private fun newPool(factory: TrackingFactory) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumPendingAcquisitions = 1,
            maximumSize = 1,
            minimumSize = 1,
            reaperDelay = 10L.minutes,
            summaryInterval = 10L.minutes
        ),
        factory,
        factory
    )

    @Test
    fun deadPoolableIsDetachedWhileRecyclingWaitsForItsPermit() = runTest {
        val factory = TrackingFactory()
        val pool = newPool(factory)
        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                pool.prepare()
                assertTrue(factory.poolable.isListenerAttached)
                factory.poolable.acquirePermit()

                factory.poolable.isAlive = false
                assertNull(pool.selectPoolableForTest())

                assertFalse(factory.poolable.isListenerAttached)
                assertFalse(factory.recycled.isCompleted)
                factory.poolable.releasePermit()
                pool.onAvailable(factory.poolable)
                withTimeout(5L.seconds) {
                    factory.recycled.await()
                }
                assertFalse(checkNotNull(factory.listenerAttachedAtRecycle))
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun retainedPoolableIsDetachedBeforeFactoryClose() = runTest {
        val factory = TrackingFactory()
        val pool = newPool(factory)
        pool.prepare()
        assertTrue(factory.poolable.isListenerAttached)

        pool.close()

        assertFalse(factory.poolable.isListenerAttached)
        assertFalse(checkNotNull(factory.listenerAttachedAtClose))
    }
}
