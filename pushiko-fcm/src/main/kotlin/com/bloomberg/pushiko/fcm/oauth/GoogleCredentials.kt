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
import com.google.auth.oauth2.ServiceAccountCredentials
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

internal const val MAX_SERVICE_ACCOUNT_METADATA_BYTES: Int = 64 * 1_024

private val ALLOWED_TOKEN_SERVER_URIS = setOf(
    URI.create("https://oauth2.googleapis.com/token"),
    URI.create("https://accounts.google.com/o/oauth2/token")
)

@JvmSynthetic
internal fun GoogleCredentials(
    metadata: File,
    proxy: InetSocketAddress? = null
): ServiceAccountCredentials {
    val bytes = metadata.readBoundedMetadata()
    return try {
        validateMetadata(bytes)
        bytes.inputStream().use { stream ->
            ServiceAccountCredentials.fromStream(stream) {
                NetHttpTransport.Builder().apply {
                    proxy?.let { setProxy(Proxy(Proxy.Type.HTTP, it)) }
                }.build()
            }.createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging")) as ServiceAccountCredentials
        }
    } finally {
        bytes.fill(0)
    }
}

private fun File.readBoundedMetadata(): ByteArray {
    require(!isFile || length() <= MAX_SERVICE_ACCOUNT_METADATA_BYTES) {
        "FCM service-account metadata exceeds the maximum size of $MAX_SERVICE_ACCOUNT_METADATA_BYTES bytes"
    }
    return inputStream().use(InputStream::readBoundedMetadata)
}

private fun InputStream.readBoundedMetadata(): ByteArray = readNBytes(MAX_SERVICE_ACCOUNT_METADATA_BYTES + 1).also {
    require(it.size <= MAX_SERVICE_ACCOUNT_METADATA_BYTES) {
        "FCM service-account metadata exceeds the maximum size of $MAX_SERVICE_ACCOUNT_METADATA_BYTES bytes"
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun validateMetadata(metadata: ByteArray) {
    val fields = try {
        metadata.inputStream().use {
            metadataJson.decodeFromStream<ServiceAccountMetadata>(it)
        }
    } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid service-account metadata", exception)
    }

    require(fields.type.isNotBlank()) { "Credential metadata field 'type' must be a non-blank string" }
    require(fields.type == "service_account") {
        "Credential metadata must have type 'service_account'"
    }
    fields.projectId.requireNotBlank("project_id").requireValidProjectId()

    val tokenServerUri = fields.tokenUri.requireNotBlank("token_uri").let { tokenUri ->
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

private fun String.requireNotBlank(name: String): String = apply {
    require(isNotBlank()) {
        "Credential metadata field '$name' must be a non-blank string"
    }
}

@Serializable
private data class ServiceAccountMetadata(
    val type: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("token_uri") val tokenUri: String
)

private val metadataJson = Json {
    ignoreUnknownKeys = true
}
