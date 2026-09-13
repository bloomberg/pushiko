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

@file:Suppress("FunctionName")

package com.bloomberg.pushiko.fcm.oauth

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val ALLOWED_TOKEN_SERVER_URIS = setOf(
    URI.create("https://oauth2.googleapis.com/token"),
    URI.create("https://accounts.google.com/o/oauth2/token")
)

@JvmSynthetic
internal fun GoogleCredentials(
    metadata: File,
    proxy: InetSocketAddress? = null
) = metadata.readBytes().let { bytes ->
    validateMetadata(bytes.decodeToString())
    bytes.inputStream().use { stream ->
        GoogleCredentials.fromStream(stream) {
            NetHttpTransport.Builder().apply {
                proxy?.let { setProxy(Proxy(Proxy.Type.HTTP, it)) }
            }.build()
        }.createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging")) as ServiceAccountCredentials
    }
}

private fun validateMetadata(metadata: String) {
    val json = try {
        Json.parseToJsonElement(metadata).jsonObject
    } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid service-account metadata", exception)
    }

    require(json.requiredString("type") == "service_account") {
        "Credential metadata must have type 'service_account'"
    }
    json.requiredString("project_id")

    val tokenServerUri = json.requiredString("token_uri").let { tokenUri ->
        try {
            URI(tokenUri)
        } catch (exception: URISyntaxException) {
            throw IllegalArgumentException("Credential metadata has an invalid token_uri", exception)
        }
    }
    require(tokenServerUri in ALLOWED_TOKEN_SERVER_URIS) {
        "Credential metadata token_uri must be an approved Google OAuth HTTPS endpoint"
    }
}

private fun JsonObject.requiredString(name: String): String {
    val value = this[name]
    require(value is JsonPrimitive && value.isString && value.content.isNotBlank()) {
        "Credential metadata field '$name' must be a non-blank string"
    }
    return value.content
}
