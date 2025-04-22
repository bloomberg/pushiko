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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class FifoBufferTest {
    @Test
    fun zeroIsEmpty(): Unit = FifoBuffer<Any>(0).run {
        assertEquals(0, size)
        assertNull(removeFirstOrNull())
    }

    @Test
    fun throwsIfCapacityIsNegative() {
        assertThrows<IllegalArgumentException> {
            FifoBuffer<Any>(-1)
        }
    }

    @Test
    fun isEmpty(): Unit = FifoBuffer<Any>(1).run {
        assertEquals(0, size)
        assertNull(removeFirstOrNull())
    }

    @Test
    fun isNotEmptyLast(): Unit = FifoBuffer<Any>(1).run {
        addLast(Any())
        assertEquals(1, size)
        assertNotNull(removeFirstOrNull())
        assertEquals(0, size)
        assertNull(removeFirstOrNull())
    }

    @Test
    fun isNotEmptyFirst(): Unit = FifoBuffer<Any>(1).run {
        addFirst(Any())
        assertEquals(1, size)
        assertNotNull(removeFirstOrNull())
        assertEquals(0, size)
        assertNull(removeFirstOrNull())
    }

    @Test
    fun removeFirstWhenEmpty(): Unit = FifoBuffer<Any>(1).run {
        assertNull(removeFirstOrNull())
    }

    @Test
    fun removeLastWhenEmpty(): Unit = FifoBuffer<Any>(1).run {
        assertNull(removeFirstOrNull())
    }

    @Test
    fun addLastThenAddFirst(): Unit = FifoBuffer<Any>(2).run {
        val last = Any().also { addLast(it) }
        val first = Any().also { addFirst(it) }
        assertSame(first, removeFirstOrNull())
        assertSame(last, removeFirstOrNull())
    }

    @Test
    fun addLastThenRemoveFirst(): Unit = FifoBuffer<Any>(1).run {
        val obj = Any().also { addLast(it) }
        assertSame(obj, removeFirstOrNull())
        assertNull(removeFirstOrNull())
    }

    @Test
    fun addFirstThenRemoveFirst(): Unit = FifoBuffer<Any>(1).run {
        val obj = Any().also { addFirst(it) }
        assertSame(obj, removeFirstOrNull())
        assertNull(removeFirstOrNull())
    }

    @Test
    fun addLastTwoThenRemoveFirstBeforeAddingAgain(): Unit = FifoBuffer<Any>(2).run {
        val first = Any().also { addLast(it) }
        val second = Any().also { addLast(it) }
        assertSame(first, removeFirstOrNull())
        addLast(first)
        assertSame(second, removeFirstOrNull())
    }

    @Test
    fun firstIsLastInFirstOut(): Unit = FifoBuffer<Any>(2).run {
        val first = Any().also { addFirst(it) }
        val second = Any().also { addFirst(it) }
        assertEquals(2, size)
        assertSame(second, removeFirstOrNull())
        assertEquals(1, size)
        assertSame(first, removeFirstOrNull())
        assertEquals(0, size)
        assertNull(removeFirstOrNull())
    }

    @Test
    fun lastIsFirstInFirstOut(): Unit = FifoBuffer<Any>(2).run {
        val first = Any().also { addLast(it) }
        val second = Any().also { addLast(it) }
        assertEquals(2, size)
        assertSame(first, removeFirstOrNull())
        assertEquals(1, size)
        assertSame(second, removeFirstOrNull())
        assertEquals(0, size)
        assertNull(removeFirstOrNull())
    }

    @Test
    fun assertFullFirst(): Unit = FifoBuffer<Any>(1).run {
        addFirst(Any())
        assertEquals(1, size)
        assertThrows<AssertionError> { addFirst(Any()) }
    }

    @Test
    fun assertFullLast(): Unit = FifoBuffer<Any>(1).run {
        addLast(Any())
        assertEquals(1, size)
        assertThrows<AssertionError> { addLast(Any()) }
    }

    @Test
    fun removeUntilFirst(): Unit = FifoBuffer<Int>(3).run {
        addLast(1)
        addLast(2)
        addLast(3)
        assertEquals(2, removeUntilFirstInclusiveOrNull { it == 2 })
        assertEquals(1, size)
        assertEquals(3, removeFirstOrNull())
    }

    @Test
    fun removeUntilFirstNone(): Unit = FifoBuffer<Int>(3).run {
        addLast(1)
        addLast(2)
        addLast(3)
        assertNull(removeUntilFirstInclusiveOrNull { it == 4 })
        assertEquals(0, size)
    }

    @Test
    fun removeLast(): Unit = FifoBuffer<Int>(3).run {
        addLast(1)
        addLast(2)
        addLast(3)
        assertEquals(3, removeLast())
        assertEquals(2, removeLast())
        assertEquals(1, removeLast())
        assertEquals(0, size)
    }

    @Test
    fun removeAll(): Unit = FifoBuffer<Int>(2).run {
        addLast(1)
        addLast(2)
        removeAll { true }
        assertEquals(0, size)
        assertNull(removeFirstOrNull())
    }

    @Test
    fun removeAllOne(): Unit = FifoBuffer<Int>(2).run {
        addLast(1)
        addLast(2)
        removeAll { it == 1 }
        assertEquals(1, size)
        assertEquals(2, removeFirstOrNull())
    }

    @Test
    fun removeAllNone(): Unit = FifoBuffer<Int>(2).run {
        addLast(1)
        addLast(2)
        removeAll { it == 3 }
        assertEquals(2, size)
        assertEquals(1, removeFirstOrNull())
        assertEquals(2, removeFirstOrNull())
    }

    @Test
    fun emptyRemoveUntilFirst(): Unit = FifoBuffer<Int>(1).run {
        assertNull(removeUntilFirstInclusiveOrNull { true })
    }

    @Test
    fun emptyIteration(): Unit = FifoBuffer<Int>(1).run {
        assertTrue(toList().isEmpty())
    }

    @Test
    fun iterate(): Unit = FifoBuffer<Int>(2).run {
        addLast(1)
        addLast(2)
        toList()
    }.run {
        assertEquals(2, size)
        assertEquals(1, first())
        assertEquals(2, this[1])
    }

    @Test
    fun iteratorThrows(): Unit = FifoBuffer<Int>(1).iterator().run {
        assertThrows<NoSuchElementException> {
            next()
        }
    }
}
