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

package com.bloomberg.pushiko.fcm.model.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class NotificationPriorityTest {
    @Test
    fun orderOfKeys() {
        val keys = NotificationPriority.entries.map { it.name }.toTypedArray()
        val sortedKeys = keys.sortedArray()
        assertTrue(sortedKeys.contentDeepEquals(keys),
            "NotificationPriority values should be listed in alphabetical order.")
    }

    @Test
    fun defaultValue() {
        assertEquals("PRIORITY_DEFAULT", NotificationPriority.DEFAULT.value)
    }

    @Test
    fun highValue() {
        assertEquals("PRIORITY_HIGH", NotificationPriority.HIGH.value)
    }

    @Test
    fun lowValue() {
        assertEquals("PRIORITY_LOW", NotificationPriority.LOW.value)
    }

    @Test
    fun maxValue() {
        assertEquals("PRIORITY_MAX", NotificationPriority.MAX.value)
    }

    @Test
    fun minValue() {
        assertEquals("PRIORITY_MIN", NotificationPriority.MIN.value)
    }

    @Test
    fun unspecifiedValue() {
        assertEquals("PRIORITY_UNSPECIFIED", NotificationPriority.UNSPECIFIED.value)
    }
}
