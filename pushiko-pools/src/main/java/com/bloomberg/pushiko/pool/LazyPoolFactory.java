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

package com.bloomberg.pushiko.pool;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.ExecutorService;

@ThreadSafe
public final class LazyPoolFactory {
    private LazyPoolFactory() {
        throw new AssertionError("LazyPoolFactory is not instantiable.");
    }

    /**
     *
     * @param pool to transform into a lazy pool.
     * @param executor from which to dispatch and then resume on; null to accept the default.
     * @return lazy pool underpinned by the given suspending pool.
     * @param <R> type of underlying resource to pool.
     * @param <P> poolable type decorating the pooled resource.
     */
    public static <R, P extends Poolable<R>> @NotNull LazyPool<@NotNull R> from(
        @NotNull
        final SuspendPool<R, P> pool,
        @Nullable
        final ExecutorService executor
    ) {
        return LazyPoolKt.asLazyPool(pool, executor);
    }
}
