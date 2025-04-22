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

package com.bloomberg.netty.ktx

import io.netty.util.concurrent.Future
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resumeWithException

/**
 * Converts this Netty [Future] into an instance of [Deferred].
 */
@Suppress("DeferredIsResult")
fun <T> Future<T>.asDeferred(): Deferred<T> = if (isDone) {
    if (isSuccess) {
        CompletableDeferred(now as T)
    } else {
        CompletableDeferred<T>().apply { completeExceptionally(cause().executionCauseOrThis()) }
    }
} else {
    CompletableDeferred<T>().apply {
        addListener {
            if (it.isSuccess) {
                complete(now)
            } else {
                completeExceptionally(it.cause().executionCauseOrThis())
            }
        }
        invokeOnCompletion {
            cancel(false)
        }
    }
}

/**
 * Awaits for the completion of this Netty [Future] without blocking.
 *
 * @return the result, or throws the corresponding exception.
 *
 * @since 0.25.12
 */
@OptIn(ExperimentalCoroutinesApi::class)
@JvmSynthetic
suspend fun <T> Future<T>.awaitKt(): T = if (isDone) {
    if (isSuccess) {
        now as T
    } else {
        throw cause().executionCauseOrThis()
    }
} else {
    suspendCancellableCoroutine { continuation ->
        addListener {
            if (it.isSuccess) {
                @Suppress("UNCHECKED_CAST")
                continuation.resume(it.now as T, null)
            } else {
                continuation.resumeWithException(it.cause().executionCauseOrThis())
            }
        }
        continuation.invokeOnCancellation {
            cancel(false)
        }
    }
}

private fun Throwable.executionCauseOrThis() = (this as? ExecutionException)?.cause ?: this
