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

package com.bloomberg.pushiko.apns

import com.bloomberg.pushiko.apns.model.Priority
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ApnsEnvironmentTest {
    @Test
    fun orderOfKeys() {
        val keys = Priority.entries.map { it.name }.toTypedArray()
        val sortedKeys = keys.sortedArray()
        assertTrue(sortedKeys.contentDeepEquals(keys),
            "ApnsEnvironment values should be listed in lexicographical order.")
    }

    @Test
    fun production() {
        assertEquals("api.push.apple.com", ApnsEnvironment.PRODUCTION.host)
        assertEquals(443, ApnsEnvironment.PRODUCTION.port)
    }

    @Test
    fun development() {
        assertEquals("api.sandbox.push.apple.com", ApnsEnvironment.DEVELOPMENT.host)
        assertEquals(443, ApnsEnvironment.DEVELOPMENT.port)
    }

    @Test
    fun lowercase() {
        assertEquals(
            setOf("development", "production"),
            ApnsEnvironment.entries.map { it.name.lowercase(Locale.US) }.toSet()
        )
    }
}
