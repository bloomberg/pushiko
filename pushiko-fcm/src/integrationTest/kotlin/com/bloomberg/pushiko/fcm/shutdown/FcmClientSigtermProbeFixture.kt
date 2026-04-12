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

package com.bloomberg.pushiko.fcm.shutdown

import com.bloomberg.pushiko.fcm.FcmClient
import java.io.File
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal suspend fun main() {
    val markerFile = Path.of(System.getProperty("pushiko.sigterm.marker"))
    val tokenServer = FakeBusyTokenServerFixture().apply(FakeBusyTokenServerFixture::start)
    val metadata = createServiceAccountMetadata(tokenServer.port)
    val closed = AtomicBoolean(false)
    val client = FcmClient {
        metadata(listOf(metadata))
        minimumConnections(0)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        @Suppress("BlockingMethodInNonBlockingContext")
        Files.writeString(markerFile, "HOOK_START\n", StandardCharsets.UTF_8)
        runCatching {
            runBlocking {
                client.close()
            }
            closed.set(true)
            Files.writeString(markerFile, "HOOK_START\nCLOSE_OK\n", StandardCharsets.UTF_8)
            println("CLOSE_OK")
        }.onFailure {
            Files.writeString(
                markerFile,
                "HOOK_START\nCLOSE_FAILED:${it::class.java.name}:${it.message}\n",
                StandardCharsets.UTF_8
            )
            System.err.println("CLOSE_FAILED: ${it::class.java.name}: ${it.message}")
        }
        tokenServer.stop()
        metadata.delete()
    })
    println("READY")
    client.joinStart()
    withContext(Dispatchers.IO) {
        CountDownLatch(1).await()
    }
    if (!closed.get()) {
        error("Probe exited unexpectedly")
    }
}

private class FakeBusyTokenServerFixture {
    private val running = AtomicBoolean(true)
    private val serverSocket = ServerSocket(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val port: Int get() = serverSocket.localPort

    @Suppress("Detekt.CognitiveComplexMethod")
    fun start() {
        scope.launch {
            while (running.get()) {
                val socket = runCatching(serverSocket::accept).getOrNull() ?: break
                launch {
                    socket.use { client ->
                        runCatching {
                            val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                            var contentLength = 0
                            while (true) {
                                val line = reader.readLine().takeUnless(String::isNullOrEmpty) ?: break
                                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                                }
                            }
                            repeat(contentLength) {
                                if (reader.read() == -1) {
                                    return@repeat
                                }
                            }
                            OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8).use { writer ->
                                writer.write("HTTP/1.1 429 OK\r\n")
                                writer.write("Connection: close\r\n\r\n")
                                writer.flush()
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket.close()
        } finally {
            scope.cancel()
        }
    }
}

private fun createServiceAccountMetadata(port: Int): File {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048)
    }
    val privateKey = keyPairGenerator.generateKeyPair().private.encoded
    val privateKeyPem = buildString {
        append("-----BEGIN PRIVATE KEY-----\\n")
        append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateKey))
        append("\\n-----END PRIVATE KEY-----\\n")
    }
    val metadata = """
        {
          "type": "service_account",
          "project_id": "sigterm-test-project",
          "private_key_id": "fake-private-key-id",
          "private_key": "$privateKeyPem",
          "client_email": "fake-service-account@sigterm-test-project.iam.gserviceaccount.com",
          "client_id": "1234567890",
          "auth_uri": "https://accounts.google.com/o/oauth2/auth",
          "token_uri": "http://127.0.0.1:$port/token",
          "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
          "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/fake-service-account%40sigterm-test-project.iam.gserviceaccount.com"
        }
    """.trimIndent()
    val path = Files.createTempFile("fcm-sigterm-", ".json")
    Files.writeString(path, metadata, StandardCharsets.UTF_8)
    return path.toFile()
}
