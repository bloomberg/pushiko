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

import com.bloomberg.pushiko.commons.assertions.assert
import javax.annotation.concurrent.NotThreadSafe

/**
 * A fixed capacity first-in-first-out buffer to which items can be added and removed. This buffer makes no attempt
 * to protect its bounds or define its behaviour should any attempt to exceed its capacity ever be made: It is always
 * the caller's responsibility to ensure the buffer's capacity is never exceeded.
 */
@NotThreadSafe
class FifoBuffer<T : Any>(capacity: Int) : Iterable<T> {
    init {
        require(capacity > -1)
    }

    private val lastIndex = capacity

    @Suppress("UNCHECKED_CAST")
    private val items = arrayOfNulls<Any>(capacity + 1) as Array<T?>
    private var first = 0
    private var last = 0

    /**
     * The current number of items held by this buffer.
     */
    var size = 0
        private set

    /**
     * Adds an item at the end of this buffer.
     *
     * @param item to add at the end.
     */
    fun addLast(item: T) {
        items[preDecrementLast()] = item
        assert("Buffer was already full") { first != last }
        ++size
    }

    /**
     * Adds an item at the start of this buffer.
     *
     * @param item to add at the start.
     */
    fun addFirst(item: T) {
        items[postIncrementFirst()] = item
        assert("Buffer was already full") { first != last }
        ++size
    }

    /**
     * Continues to remove elements until the first element to satisfy the predicate has been removed or the buffer
     * is empty.
     *
     * @param predicate to satisfy.
     *
     * @return the first removed element to satisfy the predicate, or null if the buffer is or has become empty.
     */
    inline fun removeUntilFirstInclusiveOrNull(predicate: (T) -> Boolean): T? {
        while (true) {
            (removeFirstOrNull() ?: break).let {
                if (predicate(it)) {
                    return it
                }
            }
        }
        return null
    }

    /**
     * Removes the first element added to this buffer if the buffer is not empty.
     *
     * @return the removed first element from this buffer, or null if this buffer is empty.
     */
    fun removeFirstOrNull(): T? = if (isNotEmpty()) {
        removeFirst()
    } else {
        null
    }

    /**
     * Removes all elements satisfying the predicate, for which the predicate returns `true`.
     *
     * @param predicate to satisfy.
     */
    inline fun removeAll(predicate: (T) -> Boolean) {
        repeat(size) {
            removeFirst().let {
                if (!predicate(it)) {
                    addLast(it)
                }
            }
        }
    }

    /**
     * Removes the first element added to this buffer.
     *
     * @return the removed first element from this buffer.
     */
    fun removeFirst(): T {
        val index = preDecrementFirst()
        return items[index]!!.also {
            items[index] = null
            --size
        }
    }

    /**
     * Removes the last element added to this buffer.
     *
     * @return the removed last element from this buffer.
     */
    fun removeLast(): T {
        val index = postIncrementLast()
        return items[index]!!.also {
            items[index] = null
            --size
        }
    }

    override fun iterator(): Iterator<T> = BufferIterator(this)

    @JvmSynthetic @PublishedApi
    internal fun isNotEmpty() = first != last

    private fun postIncrementFirst() = first.also {
        first = if (it == lastIndex) { 0 } else { it + 1 }
    }

    private fun postIncrementLast() = last.also {
        last = if (it == lastIndex) { 0 } else { it + 1 }
    }

    private fun preDecrementFirst() = (if (first == 0) { lastIndex } else { first - 1 }).also { first = it }

    private fun preDecrementLast() = (if (last == 0) { lastIndex } else { last - 1 }).also { last = it }

    private class BufferIterator<T : Any>(private val buffer: FifoBuffer<T>) : Iterator<T> {
        private var index = buffer.first

        override fun hasNext() = index != buffer.last

        override fun next(): T = if (hasNext()) {
            preDecrementIndex()
            buffer.items[index]!!
        } else {
            throw NoSuchElementException()
        }

        private fun preDecrementIndex() = (if (index == 0) { buffer.lastIndex } else { index - 1 }).also { index = it }
    }
}
