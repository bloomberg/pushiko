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

@file:JvmName("FcmClients")

package com.bloomberg.pushiko.fcm

import com.bloomberg.pushiko.commons.UNREACHABLE_KOTLIN_VERSION
import com.bloomberg.pushiko.commons.coroutines.CommonPoolDispatcher
import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.pushiko.commons.strings.commonPluralSuffix
import com.bloomberg.pushiko.exceptions.ClientClosedException
import com.bloomberg.pushiko.fcm.annotations.FcmMarker
import com.bloomberg.pushiko.fcm.exceptions.FcmException
import com.bloomberg.pushiko.fcm.oauth.CredentialsSession
import com.bloomberg.pushiko.fcm.oauth.GoogleCredentials
import com.bloomberg.pushiko.fcm.oauth.Session
import com.bloomberg.pushiko.health.HealthCheck
import com.bloomberg.pushiko.http.HttpClient
import com.bloomberg.pushiko.http.HttpClientProperties.Companion.OptionalHttpProperties
import com.bloomberg.pushiko.http.HttpRequest
import com.bloomberg.pushiko.http.HttpResponse
import com.bloomberg.pushiko.http.exceptions.HttpClientClosedException
import com.bloomberg.pushiko.http.retryAfterMillis
import com.bloomberg.pushiko.metrics.Gauges
import com.bloomberg.pushiko.metrics.Metrics
import com.bloomberg.pushiko.proxies.systemHttpsProxyAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.future
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_CREATED
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_OK
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.annotation.concurrent.ThreadSafe
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toKotlinDuration

private const val FCM_HOST = "fcm.googleapis.com"
private const val FCM_PORT = 443

// Empirically the Firebase Cloud Messaging peer has allowed up to 100 concurrent requests per connection.
private const val LOW_WATERMARK = 30L
private const val HIGH_WATERMARK = 150L

private const val MAX_RETRIES_DEFAULT = 2
private const val INITIAL_BACKOFF_MILLIS = 1_000L
private const val BAD_GATEWAY_BACKOFF_MILLIS = 30_000L
private const val POST = "POST"
private const val AUTHORIZATION_HEADER = "authorization"
private const val CONTENT_TYPE_HEADER = "content-type"
private const val JSON_UTF8_CONTENT_TYPE = "application/json; charset=UTF-8"

@OptIn(ExperimentalContracts::class)
@FcmMarker
@JvmSynthetic
fun FcmClient(block: FcmClient.Builder.() -> Unit): FcmClient {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return FcmClient.Builder().apply(block).build()
}

@Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
@SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
fun FcmClient(consumer: Consumer<FcmClient.Builder>) = FcmClient { consumer.accept(this) }

/**
 * A client for sending push notification messages to Firebase Cloud Messaging (FCM).
 *
 * Clients internally use the shared HTTP connection pool for connecting to FCM. They are inherently thread-safe and
 * normally intended to live until the JVM begins shutting down. Clients gracefully close in an orderly manner,
 * releasing their connections resources and closing their session. Once closed, clients can no longer be used to send
 * any notification requests.
 *
 * @see <a href="https://firebase.google.com/docs/cloud-messaging">Firebase Cloud Messaging documentation</a>
 *
 * @since 0.1.0
 */
@ThreadSafe
class FcmClient private constructor(
    private val sessions: Map<String, Session>,
    private val httpClient: HttpClient,
    javaDispatcher: CoroutineDispatcher? = null
) {
    /**
     * @since 0.24.0
     */
    @JvmField
    val healthComponent = HealthComponent()

    @JvmField
    val metricsComponent = MetricsComponent()

    private val logger = Logger()
    private val javaScope = CoroutineScope(SupervisorJob() + (javaDispatcher ?: CommonPoolDispatcher))

    /**
     * Suspends the current coroutine until this [FcmClient] has been allowed to complete all of its initial
     * preparation such as pre-populating its internal HTTP connection pool.
     *
     * @throws CancellationException if the job of the coroutine context is cancelled or completed.
     *
     * @since 0.19.0
     */
    @JvmSynthetic
    suspend fun joinStart() {
        httpClient.prepare()
    }

    /**
     * @see [joinStart]
     *
     * @since 0.22.0
     */
    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
    fun joinStartFuture(): CompletableFuture<Unit> = javaScope.future(start = CoroutineStart.UNDISPATCHED) {
        joinStart()
    }

    /**
     * Sends a push notification request to Firebase Cloud Messaging (FCM).
     *
     * Even if this attempt is completed successfully, the onward delivery of the notification from FCM to the
     * intended receiver (such as an Android app) is not necessarily immediate and is not guaranteed to succeed.
     *
     * Requests that meet with a client error response should not be retried (with the possible exception of a 429
     * response). Unregistered or invalid message tokens as cited in error responses should not be re-used.
     *
     * Even if the request has failed with an exception the notification might still make it to the intended receiver.
     * Callers might retry failed requests after a suggested time period, once the underlying issue is resolved.
     *
     * @param request to send to FCM.
     *
     * @return the parsed response received from FCM.
     *
     * @throws CancellationException if the job of the coroutine context is cancelled or completed.
     * @throws ClientClosedException if the client is closed.
     * @throws java.io.IOException
     *
     * @since 0.1.0
     */
    @JvmSynthetic
    suspend fun send(
        projectId: String,
        request: FcmRequest
    ): FcmResponse = runCatching {
        doSend(HttpRequest {
            val session = requireNotNull(sessions[projectId]) { "Unrecognised project: '$projectId'" }
            method(POST)
            authority(FCM_HOST)
            path(session.sendPath)
            header(AUTHORIZATION_HEADER, session.currentAuthorization)
            header(CONTENT_TYPE_HEADER, JSON_UTF8_CONTENT_TYPE)
            body(request.payload)
        }, request, MAX_RETRIES_DEFAULT, INITIAL_BACKOFF_MILLIS)
    }.getOrElse {
        throw if (it is HttpClientClosedException) {
            ClientClosedException
        } else {
            it
        }
    }

    /**
     * Sends a push notification request to Firebase Cloud Messaging (FCM).
     *
     * @param request to send to FCM.
     *
     * @return future which is complete once a response has been received from the peer or an exception has been
     *   raised.
     *
     * @see [send]
     *
     * @since 0.22.0
     */
    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
    fun sendFuture(
        projectId: String,
        request: FcmRequest
    ): CompletableFuture<FcmResponse> = javaScope.future(
        start = CoroutineStart.UNDISPATCHED
    ) {
        send(projectId, request)
    }

    /**
     * Gracefully closes this client. Once closed, this client can no longer be used to send notification requests.
     *
     * @throws CancellationException if the job of the coroutine context is cancelled or completed.
     *
     * @since 0.1.0
     */
    @JvmSynthetic
    suspend fun close() {
        try {
            httpClient.close()
        } finally {
            sessions.entries.forEach {
                runCatching {
                    it.value.close()
                }.onFailure { e ->
                    logger.info("Error closing session {}", it.key, e)
                }
            }
        }
    }

    /**
     * @see [close]
     *
     * @since 0.22.0
     */
    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
    fun closeFuture(): CompletableFuture<Unit> = javaScope.future(start = CoroutineStart.UNDISPATCHED) {
        close()
    }

    private tailrec suspend fun doSend(
        httpRequest: HttpRequest,
        fcmRequest: FcmRequest,
        retries: Int,
        backOffMillis: Long
    ): FcmResponse {
        httpClient.send(httpRequest).run {
            runCatching {
                use {
                    process(fcmRequest)
                }
            }.getOrElse {
                throw FcmException("Failed to parse HTTP response, code: $code", it)
            }
        }.let {
            return if (retries > 0 && it is FcmServerErrorResponse) {
                when (it.code) {
                    HttpURLConnection.HTTP_BAD_GATEWAY -> {
                        // Firebase has been seen to respond with HTML saying it had encountered a temporary error and
                        // could not complete the request, and that the request should be retried in 30 seconds time.
                        val intervalMillis = it.retryAfterMillis ?: BAD_GATEWAY_BACKOFF_MILLIS
                        logger.info("Encountered {}, retrying request once in {} milliseconds", it.code,
                            intervalMillis)
                        delay(intervalMillis)
                        doSend(httpRequest, fcmRequest, 0, 0L)
                    }
                    HttpURLConnection.HTTP_UNAVAILABLE -> {
                        // Firebase responds with a 503 when the server is overloaded and asks that the request be
                        // retried in this case. The scheduling of the retry must honour the retry-after header if
                        // this is included in the response and follow an exponential back-off mechanism.
                        // ref: https://firebase.google.com/docs/reference/fcm/rest/v1/ErrorCode
                        logger.info("Encountered {}, retrying request with {} attempt{} remaining", it.code,
                            retries, retries.commonPluralSuffix())
                        delay(maxOf(it.retryAfterMillis ?: 0L, backOffMillis))
                        doSend(httpRequest, fcmRequest, retries - 1, backOffMillis shl 1)
                    }
                    else -> it
                }
            } else {
                it
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun HttpResponse.process(request: FcmRequest) = when (code) {
        HTTP_OK -> json.decodeFromStream(FcmSuccessResponse.serializer(), body!!)
        in HTTP_CREATED until HTTP_MULT_CHOICE -> json.decodeFromStream(
            FcmSuccessResponse.serializer(),
            body!!
        ).apply {
            code = this.code
        }
        in HTTP_BAD_REQUEST until HTTP_INTERNAL_ERROR -> json.decodeFromStream(FcmClientErrorResponse.serializer(),
            body!!)
        else -> FcmServerErrorResponse(
            request,
            code,
            body?.bufferedReader(Charsets.UTF_8)?.readText(),
            retryAfterMillis()
        )
    }

    class Builder internal constructor() {
        private var executor: Executor? = null
        private val metadata = mutableListOf<File>()

        @JvmSynthetic
        internal var connectionAcquisitionTimeout: Duration? = null

        @JvmSynthetic
        internal var maximumConnections: Int? = null

        @JvmSynthetic
        internal var minimumConnections: Int? = null

        private var proxyAddress: InetSocketAddress? = systemHttpsProxyAddress(FCM_HOST)

        @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
        @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
        fun executor(value: Executor) = apply {
            executor = value
        }

        @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
        @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
        fun connectionAcquisitionTimeout(value: java.time.Duration) = apply {
            connectionAcquisitionTimeout(value.toKotlinDuration())
        }

        @JvmSynthetic
        fun connectionAcquisitionTimeout(value: Duration) = apply {
            connectionAcquisitionTimeout = value
        }

        fun metadata(files: Iterable<File>) = apply {
            metadata.addAll(files)
        }

        fun maximumConnections(value: Int) = apply {
            maximumConnections = value
        }

        fun minimumConnections(value: Int) = apply {
            minimumConnections = value
        }

        fun proxy(host: String, port: Int) = apply {
            proxyAddress = InetSocketAddress.createUnresolved(host, port)
        }

        @JvmSynthetic
        internal fun build() = FcmClient(
            metadata.associate {
                CredentialsSession(GoogleCredentials(it, proxyAddress)).run {
                    projectId to this
                }
            },
            HttpClient {
                host = FCM_HOST
                port = FCM_PORT
                httpProperties = OptionalHttpProperties(
                    connectionAcquisitionTimeout = connectionAcquisitionTimeout,
                    // Empirically Firebase Cloud Messaging closes long-lived h2 connections after 1 hour.
                    maximumConnectionAge = 59L.minutes,
                    maximumConnections = maximumConnections,
                    minimumConnections = minimumConnections,
                    unresolvedProxyAddress = proxyAddress
                )
                requiresAlpn = true
                // Empirically Firebase Cloud Messaging closes connections after 3 HTTP pings with no requests.
                monitorConnectionHealth = false
                concurrentRequestWatermark(LOW_WATERMARK, HIGH_WATERMARK)
            },
            executor?.asCoroutineDispatcher()
        )
    }

    /**
     * @since 0.24.0
     */
    @ThreadSafe
    inner class HealthComponent internal constructor() {
        /**
         * Performs a connectivity health check on this FCM client. This check does not send a request.
         *
         * @see [com.bloomberg.pushiko.http.HttpClient.ConnectivityHealthCheck.health]
         *
         * @since 0.24.0
         */
        @JvmField
        val connectivity: HealthCheck = ConnectivityHealthCheck()
    }

    @ThreadSafe
    private inner class ConnectivityHealthCheck : HealthCheck {
        override suspend fun health(timeout: Duration) = httpClient.healthComponent.connectivity.health(timeout)

        override fun healthFuture(timeout: java.time.Duration) = javaScope.future {
            health(timeout.toKotlinDuration())
        }
    }

    @ThreadSafe
    inner class MetricsComponent internal constructor() : com.bloomberg.pushiko.metrics.MetricsComponent {
        override val gauges: Gauges = object : Gauges {
            override suspend fun read(timeout: Duration) = httpClient.metricsComponent.gauges.read(timeout).let {
                Metrics(
                    connectionCount = it.connectionCount
                )
            }
        }
    }

    private companion object {
        val json = Json {
            allowStructuredMapKeys = false
            @OptIn(ExperimentalSerializationApi::class)
            explicitNulls = false
            ignoreUnknownKeys = true
            prettyPrint = false
            useAlternativeNames = false
        }
    }
}
