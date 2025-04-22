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
import java.util.LinkedList
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class QueuesKtTest {
    @Test
    fun removeUntilEmpty(): Unit = LinkedList<Int>().run {
        removeUntil { it == 1 }
        assertEquals(0, size)
    }

    @Test
    fun removeUntil(): Unit = LinkedList(listOf(1, 2, 3)).run {
        removeUntil { it == 2 }
        assertEquals(2, size)
    }

    @Test
    fun removeUntilNone(): Unit = LinkedList(listOf(1, 2, 3)).run {
        removeUntil { it == 4 }
        assertEquals(0, size)
    }

    @Test
    fun removeUntilFirstEmpty(): Unit = LinkedList<Int>().run {
        assertNull(removeUntilInclusiveOrNull { it == 1 })
        assertEquals(0, size)
    }

    @Test
    fun removeUntilFirst(): Unit = LinkedList(listOf(1, 2, 3)).run {
        assertEquals(2, removeUntilInclusiveOrNull { it == 2 })
        assertEquals(1, size)
    }

    @Test
    fun removeUntilFirstNone(): Unit = LinkedList(listOf(1, 2, 3)).run {
        assertNull(removeUntilInclusiveOrNull { it == 4 })
        assertEquals(0, size)
    }
}
