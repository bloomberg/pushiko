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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.bloomberg.pushiko.http

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http2.DefaultHttp2Headers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class HttpRequestContinuationTest {
    @Test
    fun cancellationAfterResumeReleasesUndeliveredResponse() = runTest {
        assertUndeliveredResponseReleased(cancelBeforeResume = false)
    }

    @Test
    fun cancellationBeforeResumeReleasesUndeliveredResponse() = runTest {
        assertUndeliveredResponseReleased(cancelBeforeResume = true)
    }

    private suspend fun TestScope.assertUndeliveredResponseReleased(cancelBeforeResume: Boolean) {
        lateinit var continuation: HttpRequestContinuation
        var delivered = false
        val request = async {
            suspendCancellableCoroutine<HttpResponse> {
                continuation = HttpRequestContinuation(HttpRequest {}, mock(), it)
            }.use {
                delivered = true
            }
        }
        runCurrent()
        val body = Unpooled.buffer().writeByte(1)
        try {
            val response = HttpResponse(200, DefaultHttp2Headers(), body)
            assertEquals(2, body.refCnt())
            if (cancelBeforeResume) {
                request.cancel()
            }
            continuation.resume(response)
            if (!cancelBeforeResume) {
                request.cancel()
            }
            runCurrent()
            request.join()
            assertFalse(delivered)
            // Stream removal releases the original reference; the undelivered response must release its retain.
            body.release()
            assertEquals(0, body.refCnt())
        } finally {
            if (body.refCnt() > 0) {
                body.release(body.refCnt())
            }
        }
    }

    @Test
    fun deliveredResponseRemainsOpenUntilCallerClosesIt() = runTest {
        lateinit var continuation: HttpRequestContinuation
        val body = Unpooled.buffer().writeByte(1)
        try {
            val request = async {
                suspendCancellableCoroutine<HttpResponse> {
                    continuation = HttpRequestContinuation(HttpRequest {}, mock(), it)
                }.use {
                    assertEquals(1, body.refCnt())
                    assertEquals(1, it.body!!.read())
                }
            }
            runCurrent()
            continuation.resume(HttpResponse(200, DefaultHttp2Headers(), body))
            body.release()
            runCurrent()
            request.await()
            assertEquals(0, body.refCnt())
        } finally {
            if (body.refCnt() > 0) {
                body.release(body.refCnt())
            }
        }
    }
}
