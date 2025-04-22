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

package com.bloomberg.pushiko.pool

import javax.annotation.concurrent.NotThreadSafe

@NotThreadSafe
abstract class Poolable<out R : Any>(
    @JvmField
    @PublishedApi
    internal val value: R
) {
    var allocatedPermits = 0
        private set

    abstract val maximumPermits: Int

    abstract val isAlive: Boolean

    abstract val isCanAcquire: Boolean

    abstract val isShouldAcquire: Boolean

    fun acquirePermit() = apply {
        ++allocatedPermits
    }

    fun releasePermit() {
        --allocatedPermits
    }

    open suspend fun summarize(appendable: Appendable) = Unit
}
