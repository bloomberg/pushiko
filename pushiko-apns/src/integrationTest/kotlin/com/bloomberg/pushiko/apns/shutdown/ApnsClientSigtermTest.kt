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

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

internal class ApnsClientSigtermTest {
    private val markerPath = Files.createTempFile("apns-sigterm-marker-", ".txt").apply {
        toFile().deleteOnExit()
    }

    @AfterTest
    fun tearDown() {
        markerPath.toFile().delete()
    }

    @Test
    fun sigtermTriggersFastClientShutdown() {
        val javaBinary = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            javaBinary,
            "-Dpushiko.sigterm.marker=$markerPath",
            "-cp",
            classpath,
            "com.bloomberg.pushiko.apns.shutdown.ApnsClientSigtermProbeFixtureKt"
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val ready = CountDownLatch(1)
        val reader = thread(start = true, isDaemon = true) {
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    output.appendLine(line)
                    if (line.contains("READY")) {
                        ready.countDown()
                    }
                }
            }
        }
        assertTrue(ready.await(15L, TimeUnit.SECONDS), "Probe did not become ready. Output:\n$output")
        process.destroy()
        val exited = process.waitFor(15L, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
        }
        reader.join(2_000L)
        val marker = Files.readString(markerPath)
        assertTrue(exited, "Child JVM did not exit within timeout after SIGTERM. Output:\n$output")
        assertTrue(marker.contains("HOOK_START"), "Shutdown hook did not run. Output:\n$output")
        assertTrue(marker.contains("CLOSE_OK"), "Shutdown hook did not report successful close. " +
            "Marker:\n$marker\nOutput:\n$output")
    }
}
