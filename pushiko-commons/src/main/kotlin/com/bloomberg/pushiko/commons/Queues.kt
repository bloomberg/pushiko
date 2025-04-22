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

package com.bloomberg.pushiko.commons

import java.util.Queue

/**
 * Continues to remove elements until the first element to satisfy the predicate has been encountered or the buffer
 * is empty.
 *
 * @param predicate to satisfy.
 */
inline fun <T : Any> Queue<T>.removeUntil(predicate: (T) -> Boolean) {
    while (true) {
        (peek() ?: break).let {
            if (predicate(it)) {
                return
            }
            remove()
        }
    }
}

/**
 * Continues to remove elements until the first element to satisfy the predicate has been removed or the buffer
 * is empty.
 *
 * @param predicate to satisfy.
 *
 * @return the first removed element to satisfy the predicate, or null if the buffer is or has become empty.
 */
inline fun <T : Any> Queue<T>.removeUntilInclusiveOrNull(predicate: (T) -> Boolean): T? {
    while (true) {
        (poll() ?: break).let {
            if (predicate(it)) {
                return it
            }
        }
    }
    return null
}
