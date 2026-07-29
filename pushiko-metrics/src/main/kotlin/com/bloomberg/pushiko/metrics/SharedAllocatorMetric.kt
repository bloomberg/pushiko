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
public object SharedAllocatorMetric {
    private val metric = com.bloomberg.pushiko.http.netty.SharedAllocatorMetric

    public val chunkSize: Int
        get() = metric.chunkSize

    public val directArenasCount: Int
        get() = metric.directArenasCount

    public val heapArenasCount: Int
        get() = metric.heapArenasCount

    public val normalCacheSize: Int
        get() = metric.normalCacheSize

    public val pinnedDirectMemory: Long
        get() = metric.pinnedDirectMemory

    public val pinnedHeapMemory: Long
        get() = metric.pinnedHeapMemory

    public val smallCacheSize: Int
        get() = metric.smallCacheSize

    public val threadLocalCachesCount: Int
        get() = metric.threadLocalCachesCount

    public val usedDirectMemory: Long
        get() = metric.usedDirectMemory

    public val usedHeapMemory: Long
        get() = metric.usedHeapMemory

    /**
     * This operation could be expensive.
     *
     * @return all the metrics of the allocator.
     */
    public fun dumpStatistics(): String = metric.dumpStatistics()
}
