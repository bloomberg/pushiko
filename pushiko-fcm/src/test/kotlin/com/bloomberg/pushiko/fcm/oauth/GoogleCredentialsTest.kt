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
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class GoogleCredentialsTest {
    private val metadata = File(javaClass.classLoader.getResource("firebase_metadata.json")!!.toURI())

    @Test
    fun fromMetadataFile() {
        val credentials = GoogleCredentials(metadata)
        assertEquals("foo", credentials.projectId)
        assertContentEquals(listOf("https://www.googleapis.com/auth/firebase.messaging"), credentials.scopes)
    }

    @Test
    fun currentGoogleTokenEndpoint() = withMetadataFile(
        metadataWith("token_uri" to JsonPrimitive("https://oauth2.googleapis.com/token"))
    ) { file ->
        assertEquals("foo", GoogleCredentials(file).projectId)
    }

    @Test
    fun rejectsUnapprovedTokenEndpoints() {
        listOf(
            "http://oauth2.googleapis.com/token",
            "http://127.0.0.1/token",
            "https://127.0.0.1/token",
            "https://metadata.google.internal/computeMetadata/v1",
            "https://example.com/token",
            "https://oauth2.googleapis.com/not-token",
            "https://user@oauth2.googleapis.com/token",
            "https://oauth2.googleapis.com:443/token",
            "https://oauth2.googleapis.com/token?audience=example",
            "https://oauth2.googleapis.com/token#fragment",
            "oauth2.googleapis.com/token",
            "not a URI"
        ).forEach { tokenUri ->
            withMetadataFile(metadataWith("token_uri" to JsonPrimitive(tokenUri))) { file ->
                assertFailsWith<IllegalArgumentException>(tokenUri) {
                    GoogleCredentials(file)
                }
            }
        }
    }

    @Test
    fun rejectsInvalidRequiredFields() {
        listOf(
            "type" to JsonPrimitive("authorized_user"),
            "type" to JsonPrimitive(" "),
            "project_id" to JsonPrimitive(" "),
            "token_uri" to JsonPrimitive(" "),
            "token_uri" to JsonPrimitive(1)
        ).forEach { (name, value) ->
            withMetadataFile(metadataWith(name to value)) { file ->
                assertFailsWith<IllegalArgumentException>(name) {
                    GoogleCredentials(file)
                }
            }
        }
    }

    @Test
    fun rejectsMissingRequiredFields() {
        listOf("type", "project_id", "token_uri").forEach { name ->
            withMetadataFile(metadataWith(name to null)) { file ->
                assertFailsWith<IllegalArgumentException>(name) {
                    GoogleCredentials(file)
                }
            }
        }
    }

    @Test
    fun rejectsMalformedMetadata() {
        listOf("", "not JSON", "[]", "{\"type\":").forEach { content ->
            withMetadataFile(content) { file ->
                assertFailsWith<IllegalArgumentException> {
                    GoogleCredentials(file)
                }
            }
        }
    }

    private fun metadataWith(field: Pair<String, JsonElement?>): String {
        val fields = Json.parseToJsonElement(metadata.readText()).jsonObject.toMutableMap()
        field.second?.let { fields[field.first] = it } ?: fields.remove(field.first)
        return JsonObject(fields).toString()
    }

    private fun <T> withMetadataFile(content: String, block: (File) -> T): T {
        val path = createTempFile(prefix = "firebase_metadata", suffix = ".json")
        return try {
            path.writeText(content)
            block(path.toFile())
        } finally {
            path.deleteIfExists()
        }
    }
}
