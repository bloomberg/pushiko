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

package com.bloomberg.pushiko.apns.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PushTypeTest {
    @Test
    fun orderOfKeys() {
        val keys = PushType.entries.map { it.name }.toTypedArray()
        val sortedKeys = keys.sortedArray()
        assertTrue(sortedKeys.contentDeepEquals(keys),
            "ApnsPushType values should be listed in lexicographical order.")
    }

    @Test
    fun alert() {
        assertEquals("alert", PushType.ALERT.value)
    }

    @Test
    fun background() {
        assertEquals("background", PushType.BACKGROUND.value)
    }

    @Test
    fun complication() {
        assertEquals("complication", PushType.COMPLICATION.value)
    }

    @Test
    fun fileProvider() {
        assertEquals("fileprovider", PushType.FILE_PROVIDER.value)
    }

    @Test
    fun liveActivity() {
        assertEquals("liveactivity", PushType.LIVE_ACTIVITY.value)
    }

    @Test
    fun location() {
        assertEquals("location", PushType.LOCATION.value)
    }

    @Test
    fun mdm() {
        assertEquals("mdm", PushType.MDM.value)
    }

    @Test
    fun voip() {
        assertEquals("voip", PushType.VOIP.value)
    }
}
