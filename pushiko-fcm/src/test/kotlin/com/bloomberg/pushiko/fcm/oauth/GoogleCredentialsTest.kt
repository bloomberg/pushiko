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

package com.bloomberg.pushiko.fcm.oauth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class GoogleCredentialsTest {
    @Test
    fun fromMetadataFile() {
        val metadata = File(javaClass.classLoader.getResource("firebase_metadata.json")!!.toURI())
        val credentials = GoogleCredentials(metadata)
        assertEquals("foo", credentials.projectId)
        assertContentEquals(listOf("https://www.googleapis.com/auth/firebase.messaging"), credentials.scopes)
    }
}
