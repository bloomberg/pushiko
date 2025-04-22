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

/**
 * @since 0.25.3
 */
object SharedAllocatorMetric {
    private val metric = com.bloomberg.pushiko.http.netty.SharedAllocatorMetric

    val chunkSize: Int
        get() = metric.chunkSize

    val directArenasCount: Int
        get() = metric.directArenasCount

    val heapArenasCount: Int
        get() = metric.heapArenasCount

    val normalCacheSize: Int
        get() = metric.normalCacheSize

    val pinnedDirectMemory: Long
        get() = metric.pinnedDirectMemory

    val pinnedHeapMemory: Long
        get() = metric.pinnedHeapMemory

    val smallCacheSize: Int
        get() = metric.smallCacheSize

    val threadLocalCachesCount: Int
        get() = metric.threadLocalCachesCount

    val usedDirectMemory: Long
        get() = metric.usedDirectMemory

    val usedHeapMemory: Long
        get() = metric.usedHeapMemory

    /**
     * This operation could be expensive.
     *
     * @return all the metrics of the allocator.
     */
    fun dumpStatistics(): String = metric.dumpStatistics()
}
