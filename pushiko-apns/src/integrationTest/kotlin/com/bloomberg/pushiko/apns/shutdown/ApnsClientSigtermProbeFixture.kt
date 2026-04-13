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

package com.bloomberg.pushiko.apns.shutdown

import com.bloomberg.pushiko.apns.ApnsClient
import com.bloomberg.pushiko.apns.ApnsEnvironment
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal suspend fun main() {
    val markerFile = Path.of(System.getProperty("pushiko.sigterm.marker"))
    val p12File = File(checkNotNull(
        Thread.currentThread().contextClassLoader.getResource("keystore.pkcs12")
    ) { "keystore.pkcs12 not found on classpath" }.toURI())
    val closed = AtomicBoolean(false)
    val client = ApnsClient {
        environment(ApnsEnvironment.DEVELOPMENT)
        clientCredentials(p12File, "changeit".toCharArray())
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
