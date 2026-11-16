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

package com.bloomberg.pushiko.pools

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bloomberg.pushiko.pools.exceptions.PoolClosedException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class CommonMuxPoolCreationCancellationTest {
    private fun newPool(factory: Factory<AnyPoolable>) = CommonMuxPool(
        configuration = poolConfiguration(
            maximumSize = 1,
            minimumSize = 0,
            reaperDelay = 10.minutes,
            summaryInterval = Duration.INFINITE
        ),
        factory,
        mock<Recycler<Any>>()
    )

    private suspend fun withPoolLogs(block: suspend (List<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(CommonMuxPool::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block(appender.list)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun closingPoolDoesNotLogCancelledExtraCreationAsFailure() = runBlocking {
        withPoolLogs { logs ->
            val creating = CompletableDeferred<Unit>()
            val factory = mock<Factory<AnyPoolable>> {
                onBlocking { make() } doSuspendableAnswer {
                    creating.complete(Unit)
                    awaitCancellation()
                }
            }
            val pool = newPool(factory)
            try {
                val acquisition = async {
                    runCatching { pool.withPermit(Duration.INFINITE) { } }
                }
                withTimeout(5.seconds) { creating.await() }

                withTimeout(5.seconds) { pool.close() }

                assertSame(PoolClosedException, acquisition.await().exceptionOrNull())
                assertTrue(
                    logs.none { it.message == "Failed to create extra" },
                    "Closing the pool must not report cancelled creation as a failure"
                )
            } finally {
                pool.close()
            }
        }
    }

    @Test
    fun extraCreationStillLogsFactoryFailures() = runBlocking {
        withPoolLogs { logs ->
            val failure = IOException("Simulated connection failure")
            val factory = mock<Factory<AnyPoolable>> {
                onBlocking { make() } doSuspendableAnswer { throw failure }
            }
            val pool = newPool(factory)
            try {
                val exception = assertFailsWith<IOException> { pool.withPermit(5.seconds) { } }
                pool.close()

                assertEquals(failure.message, exception.message)
                val warning = logs.single { it.message == "Failed to create extra" }
                assertEquals(Level.WARN, warning.level)
                assertEquals(IOException::class.java.name, warning.throwableProxy.className)
                assertEquals(failure.message, warning.throwableProxy.message)
            } finally {
                pool.close()
            }
        }
    }
}
