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
 * Creates and owns the resources used by a pool.
 *
 * Implementations must be thread-safe. Property access must return promptly and must not throw. Suspending functions
 * must use suspension or an appropriate dispatcher for blocking work rather than blocking their calling thread.
 */
@ThreadSafe
public interface Factory<P : Any> {
    /** Current number of resources owned by this factory. Must return promptly and must not throw. */
    public val allocations: Int

    /** Releases all resources owned by this factory. */
    public suspend fun close()

    /** Creates one resource. */
    public suspend fun make(): P
}
