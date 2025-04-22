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

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class VisibilityTest {
    @Test
    fun orderOfKeys() {
        val keys = Visibility.entries.map { it.name }.toTypedArray()
        val sortedKeys = keys.sortedArray()
        assertTrue(sortedKeys.contentDeepEquals(keys),
            "Visibility values should be listed in alphabetical order.")
    }

    @Test
    fun private() {
        assertEquals("PRIVATE", Visibility.PRIVATE.value)
    }

    @Test
    fun public() {
        assertEquals("PUBLIC", Visibility.PUBLIC.value)
    }

    @Test
    fun secret() {
        assertEquals("SECRET", Visibility.SECRET.value)
    }

    @Test
    fun unspecified() {
        assertEquals("VISIBILITY_UNSPECIFIED", Visibility.UNSPECIFIED.value)
    }
}
