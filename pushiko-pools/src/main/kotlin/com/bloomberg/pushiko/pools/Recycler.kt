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

package com.bloomberg.pushiko.pools

import javax.annotation.concurrent.ThreadSafe

/**
 * Disposes resources removed from a pool.
 *
 * Implementations must be thread-safe, return promptly and not throw. A pool may invoke this callback away from its
 * control dispatcher. Unexpected failures are isolated and logged, but failed disposal is not retried.
 */
@ThreadSafe
public interface Recycler<R : Any> {
    /** Disposes [obj]. */
    public fun recycle(obj: R)
}
