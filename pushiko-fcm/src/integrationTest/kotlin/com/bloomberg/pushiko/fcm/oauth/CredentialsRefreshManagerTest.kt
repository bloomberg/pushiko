/*
 * Copyright 2026 Bloomberg Finance L.P.
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

import com.bloomberg.pushiko.proxies.systemHttpsProxyAddress
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class CredentialsRefreshManagerTest {
    @Test
    fun initExitsForInvalidServiceAccountAgainstRealGoogleBackend() {
        assertFailsWith<IOException> {
            CredentialsRefreshManager(
                credentials = fakeCredentials(URI.create("https://oauth2.googleapis.com/token")),
                dispatcher = Dispatchers.IO
            )
        }
    }

    @Test
    fun initRetriesOnTransientHttpErrorBeforeSucceeding() {
        val responses: ArrayDeque<Pair<Int, String>> = ArrayDeque<Pair<Int, String>>().apply {
            addLast(503 to "")
            addLast(200 to """{"access_token":"fake-token","token_type":"Bearer","expires_in":3600}""")
        }
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/token") { exchange ->
                exchange.requestBody.use(InputStream::readBytes)
                val (status, body) = responses.removeFirst()
                val bytes = body.toByteArray()
                exchange.responseHeaders["Content-Type"] = listOf("application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            CredentialsRefreshManager(
                credentials = fakeCredentials(URI.create("http://localhost:${server.address.port}/token")),
                dispatcher = Dispatchers.IO
            ).stop()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun initFailsFastOnNonRetryableLocalHttpError() {
        val body = """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""".toByteArray()
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/token") { exchange ->
                exchange.requestBody.use(InputStream::readBytes)
                exchange.responseHeaders["Content-Type"] = listOf("application/json")
                exchange.sendResponseHeaders(401, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            assertFailsWith<IOException> {
                CredentialsRefreshManager(
                    credentials = fakeCredentials(URI.create("http://localhost:${server.address.port}/token")),
                    dispatcher = Dispatchers.IO
                )
            }
        } finally {
            server.stop(0)
        }
    }

    private fun fakeCredentials(tokenUri: URI): ServiceAccountCredentials {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }
        val privateKey = keyPairGenerator.generateKeyPair().private.encoded
        val privateKeyPem = buildString {
            append("-----BEGIN PRIVATE KEY-----\\n")
            append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateKey))
            append("\\n-----END PRIVATE KEY-----\\n")
        }
        val json = """
            {
                "type": "service_account",
                "project_id": "fake-project-id",
                "private_key_id": "fake-private-key-id",
                "private_key": "$privateKeyPem",
                "client_email": "fake-service-account@fake-project-id.iam.gserviceaccount.com",
                "client_id": "1234567890",
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "$tokenUri",
                "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
                "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/fake-service-account%40fake-project-id.iam.gserviceaccount.com"
            }
        """.trimIndent()
        return ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)).use { stream ->
            GoogleCredentials.fromStream(stream) {
                NetHttpTransport.Builder().apply {
                    systemHttpsProxyAddress(tokenUri.host)?.let { setProxy(Proxy(Proxy.Type.HTTP, it)) }
                }.build()
            }.createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging")) as ServiceAccountCredentials
        }
    }
}
