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

@JvmSynthetic
internal fun GoogleCredentials(
    metadata: File,
    proxy: InetSocketAddress? = null
) = metadata.inputStream().use { stream ->
    GoogleCredentials.fromStream(stream) {
        NetHttpTransport.Builder().apply {
            proxy?.let { setProxy(Proxy(Proxy.Type.HTTP, it)) }
        }.build()
    }.createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging")) as ServiceAccountCredentials
}
