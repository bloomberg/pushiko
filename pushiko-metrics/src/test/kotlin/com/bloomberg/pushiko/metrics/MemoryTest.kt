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

package com.bloomberg.pushiko.metrics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class MemoryTest {
    @Test
    fun chunkSize() {
        assertEquals(4 * 1_024 * 1_024, SharedAllocatorMetric.chunkSize)
    }

    @Test
    fun directArenasCount() {
        assertTrue(SharedAllocatorMetric.directArenasCount > 0)
    }

    @Test
    fun heapArenasCount() {
        assertTrue(SharedAllocatorMetric.heapArenasCount > 0)
    }

    @Test
    fun normalCacheSize() {
        assertEquals(64, SharedAllocatorMetric.normalCacheSize)
    }

    @Test
    fun pinnedDirectMemoryInitiallyZero() {
        assertEquals(0L, SharedAllocatorMetric.pinnedDirectMemory)
    }

    @Test
    fun pinnedHeapMemoryInitiallyZero() {
        assertEquals(0L, SharedAllocatorMetric.pinnedHeapMemory)
    }

    @Test
    fun smallCacheSize() {
        assertEquals(256, SharedAllocatorMetric.smallCacheSize)
    }

    @Test
    fun threadLocalCachesCountInitiallyZero() {
        assertEquals(0, SharedAllocatorMetric.threadLocalCachesCount)
    }

    @Test
    fun usedDirectMemoryInitiallyZero() {
        assertEquals(0L, SharedAllocatorMetric.usedDirectMemory)
    }

    @Test
    fun usedHeapMemoryInitiallyZero() {
        assertEquals(0L, SharedAllocatorMetric.usedHeapMemory)
    }

    @Test
    fun dumpStatistics() {
        assertTrue(SharedAllocatorMetric.dumpStatistics().isNotBlank())
    }
}
