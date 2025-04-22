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

package com.bloomberg.pushiko.apns.keys

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class Pkcs12Test {
    @Test
    fun pkcs12PrivateKeyEntries() {
        val keys = File(javaClass.classLoader.getResource("keystore.pkcs12")!!.toURI()).inputStream().use {
            it.pkcs12PrivateKeyEntries("changeit".toCharArray()).toList()
        }
        assertEquals(1, keys.size)
        val entry = keys.first()
        assertEquals(2, entry.attributes.size)
        assertNotNull(entry.attributes.firstOrNull { it.value == "BLP" })
    }
}
