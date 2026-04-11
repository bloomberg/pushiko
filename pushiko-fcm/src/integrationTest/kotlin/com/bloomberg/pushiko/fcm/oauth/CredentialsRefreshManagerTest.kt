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
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Proxy
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class CredentialsRefreshManagerTest {
    @Test
    fun initExitsForInvalidServiceAccountAgainstRealGoogleBackend() {
        assertFailsWith<IOException> {
            CredentialsRefreshManager(
                credentials = fakeCredentials(),
                dispatcher = Dispatchers.IO
            )
        }
    }

    private fun fakeCredentials(): ServiceAccountCredentials {
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
                "token_uri": "https://oauth2.googleapis.com/token",
                "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
                "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/fake-service-account%40fake-project-id.iam.gserviceaccount.com"
            }
        """.trimIndent()
        return ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)).use { stream ->
            GoogleCredentials.fromStream(stream) {
                NetHttpTransport.Builder().apply {
                    systemHttpsProxyAddress("fcm.googleapis.com")?.let { setProxy(Proxy(Proxy.Type.HTTP, it)) }
                }.build()
            }.createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging")) as ServiceAccountCredentials
        }
    }
}
