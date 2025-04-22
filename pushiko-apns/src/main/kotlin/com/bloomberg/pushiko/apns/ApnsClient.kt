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

@file:JvmName("ApnsClients")

package com.bloomberg.pushiko.apns

import com.bloomberg.pushiko.apns.annotations.ApnsMarker
import com.bloomberg.pushiko.apns.certificates.willExpireWithin
import com.bloomberg.pushiko.apns.exceptions.ApnsException
import com.bloomberg.pushiko.apns.keys.pkcs12PrivateKeyEntries
import com.bloomberg.pushiko.metrics.Gauges
import com.bloomberg.pushiko.metrics.Metrics
import com.bloomberg.pushiko.commons.UNREACHABLE_KOTLIN_VERSION
import com.bloomberg.pushiko.commons.coroutines.CommonPoolDispatcher
import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.pushiko.exceptions.ClientClosedException
import com.bloomberg.pushiko.health.HealthCheck
import com.bloomberg.pushiko.http.EventLoopGroupType
import com.bloomberg.pushiko.http.HttpClient
import com.bloomberg.pushiko.http.HttpClientProperties
import com.bloomberg.pushiko.http.HttpRequest
import com.bloomberg.pushiko.http.HttpResponse
import com.bloomberg.pushiko.http.exceptions.HttpClientClosedException
import com.bloomberg.pushiko.proxies.systemHttpsProxyAddress
import kotlinx.coroutines.CancellationException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_CREATED
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_OK
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.annotation.concurrent.NotThreadSafe
import javax.annotation.concurrent.ThreadSafe
import kotlin.time.toKotlinDuration

private const val TOKEN_PATH_CAPACITY = 128
private fun String.deviceTokenPath() = StringBuilder(TOKEN_PATH_CAPACITY).append("/3/device/").append(this)
private const val POST = "POST"
private const val APNS_COLLAPSE_ID_HEADER = "apns-collapse-id"
private const val APNS_EXPIRATION_HEADER = "apns-expiration"
private const val APNS_ID_HEADER = "apns-id"
private const val APNS_PRIORITY_HEADER = "apns-priority"
private const val APNS_PUSH_TYPE_HEADER = "apns-push-type"
private const val APNS_TOPIC_HEADER = "apns-topic"
private const val APNS_UNIQUE_ID_HEADER = "apns-unique-id"

// Empirically the Apple Push Notification service peer allows up to 1000 concurrent requests per connection.
private const val DEFAULT_REQUESTS_WATER_MARK_LOW = 500L
private const val DEFAULT_REQUESTS_WATER_MARK_HIGH = 1200L

private fun ApnsEnvironment.maxConnectionAge() = when (this) {
    ApnsEnvironment.PRODUCTION -> Duration.INFINITE
    ApnsEnvironment.DEVELOPMENT -> 10L.minutes
}

/**
 * Constructs a fully configured [ApnsClient] instance.
 *
 * @param block for configuring the client.
 *
 * @return client instance configured using the received lambda.
 *
 * @sample com.bloomberg.pushiko.apns.cli.createClient
 */
@OptIn(ExperimentalContracts::class)
@ApnsMarker
@JvmSynthetic
fun ApnsClient(block: ApnsClient.Builder.() -> Unit): ApnsClient {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return ApnsClient.Builder().apply(block).build()
}

@Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
@SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
fun ApnsClient(consumer: Consumer<ApnsClient.Builder>) = ApnsClient { consumer.accept(this) }

private fun ApnsEnvironment.eventLoopGroupType() = when (this) {
    ApnsEnvironment.PRODUCTION -> EventLoopGroupType.PRIMARY
    ApnsEnvironment.DEVELOPMENT -> EventLoopGroupType.SECONDARY
}

/**
 * A client for sending push notification messages to Apple Push Notifications service (APNs).
 *
 * Clients internally use an HTTP connection pool for connecting to APNs. They are inherently thread-safe and
 * normally intended to live until the JVM begins shutting down. Clients gracefully close in an orderly manner,
 * releasing their connections resources. Once closed, clients can no longer be used to send any notification
 * requests.
 *
 * @since 0.12.0
 */
@ThreadSafe
class ApnsClient private constructor(
    private val httpClient: HttpClient,
    javaDispatcher: CoroutineDispatcher? = null
) {
    private val logger = Logger()
    private val javaScope = CoroutineScope(SupervisorJob() + (javaDispatcher ?: CommonPoolDispatcher))

    /**
     * @since 0.24.0
     */
    @JvmField
    val healthComponent = HealthComponent()

    @JvmField
    val metricsComponent = MetricsComponent()

    /**
     * Suspends the current coroutine until this [ApnsClient] has been allowed to complete all of its initial
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
     * Sends a push notification request to Apple Push Notification service (APNs).
     *
     * Even if this attempt is completed successfully, the onward delivery of the notification from APNs to the
     * intended receiver (such as an iOS app) is not necessarily immediate and is not guaranteed to succeed.
     *
     * Requests that meet with a client error response should not be retried (with the possible exception of a 429
     * response). Unregistered or invalid device tokens as cited in error responses should not be re-used.
     *
     * Even if the request has failed with an exception the notification might still make it to the intended receiver.
     * Callers might retry failed requests after a suggested time period, once the underlying issue is resolved.
     *
     * @param request to send to APNs.
     *
     * @return the parsed response received from APNs.
     *
     * @throws ClientClosedException if the client is closed.
     * @throws CancellationException if the job of the coroutine context is cancelled or completed.
     * @throws java.io.IOException
     *
     * @since 0.12.0
     */
    @JvmSynthetic
    suspend fun send(
        request: ApnsRequest
    ): ApnsResponse = runCatching {
        doSend(HttpRequest {
            method(POST)
            path(request.deviceToken.deviceTokenPath())
            request.headers.run {
                collapseId?.let { header(APNS_COLLAPSE_ID_HEADER, it) }
                expiration?.let { header(APNS_EXPIRATION_HEADER, it.epochSecond) }
                id?.let { header(APNS_ID_HEADER, it.toString()) }
                priority?.let { header(APNS_PRIORITY_HEADER, it.code) }
                pushType?.let { header(APNS_PUSH_TYPE_HEADER, it.value) }
                header(APNS_TOPIC_HEADER, topic)
            }
            wantsResponseBody = false
            body(request.payload)
        })
    }.getOrElse {
        throw if (it is HttpClientClosedException) {
            ClientClosedException
        } else {
            it
        }
    }

    /**
     * Sends a push notification request to Apple Push Notification service (APNs).
     *
     * @param request to send to APNs.
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
    fun sendFuture(request: ApnsRequest): CompletableFuture<ApnsResponse> = javaScope.future(
        start = CoroutineStart.UNDISPATCHED
    ) {
        send(request)
    }

    /**
     * Gracefully closes this client. Once closed, this client can no longer be used to send notification requests.
     *
     * @throws CancellationException if the job of the coroutine context is cancelled or completed.
     *
     * @since 0.12.0
     */
    @JvmSynthetic
    suspend fun close() {
        httpClient.close()
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

    private suspend inline fun doSend(httpRequest: HttpRequest) = httpClient.send(httpRequest).run {
        runCatching {
            use { process() }
        }.getOrElse {
            throw ApnsException("Failed to parse HTTP response, code: $code", it)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun HttpResponse.process() = when (code) {
        HTTP_OK -> ApnsSuccessResponse(apnsId, apnsUniqueId)
        in HTTP_CREATED until HTTP_MULT_CHOICE -> {
            // APNs should not ever respond with 201-299. However, if it does accept this as success and
            // log a warning of this event in case this interpretation is not appropriate.
            logger.warn("Received response with unexpected status code {} from APNs peer", code)
            ApnsSuccessResponse(apnsId, apnsUniqueId, code)
        }
        in HTTP_BAD_REQUEST until HTTP_INTERNAL_ERROR ->
            jsonDeserializer.decodeFromStream(ApnsClientErrorResponse.serializer(), body!!).apply {
                apnsId = this@process.apnsId
                code = this@process.code
            }
        else -> ApnsServerErrorResponse(code, body?.bufferedReader(Charsets.UTF_8)?.use { it.readText() })
    }

    private val HttpResponse.apnsId: CharSequence
        get() = header(APNS_ID_HEADER)!!

    private val HttpResponse.apnsUniqueId: CharSequence?
        get() = header(APNS_UNIQUE_ID_HEADER)

    internal companion object {
        @JvmSynthetic
        val jsonDeserializer = Json {
            @OptIn(ExperimentalSerializationApi::class)
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }

    /**
     * A re-usable builder for constructing fully configured [ApnsClient] instances.
     */
    @NotThreadSafe
    class Builder internal constructor() {
        private val logger = Logger()

        @JvmSynthetic
        internal var environment: ApnsEnvironment = ApnsEnvironment.PRODUCTION

        @JvmSynthetic
        internal var connectionAcquisitionTimeout: Duration? = null

        @JvmSynthetic
        internal var maximumConnections: Int? = null

        @JvmSynthetic
        internal var minimumConnections: Int? = null

        @JvmSynthetic
        internal var proxyAddress: InetSocketAddress? = null

        private lateinit var p12File: File
        private var keyPassword: CharArray? = null
        private var executor: Executor? = null

        /**
         * TLS credentials for the client given as a pkcs12 file. This file must contain a single X.509 certificate
         * and private key pair, and the keystore password and alias password are assumed to be the same. The
         * credentials must correspond to the chosen APNs environment.
         */
        fun clientCredentials(p12File: File, password: CharArray) = apply {
            this.p12File = p12File
            keyPassword = password.copyOf()
        }

        fun environment(value: ApnsEnvironment) = apply {
            environment = value
        }

        @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
        @SinceKotlin(UNREACHABLE_KOTLIN_VERSION)
        fun executor(value: Executor) = apply {
            executor = value
        }

        fun connectionAcquisitionTimeout(value: Duration) = apply {
            connectionAcquisitionTimeout = value
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
        internal fun build() = try {
            internalBuild()
        } finally {
            keyPassword?.fill(0.toChar())
        }

        private fun internalBuild() = ApnsClient(HttpClient {
            eventLoopGroupType = environment.eventLoopGroupType()
            host = environment.host
            port = environment.port
            requiresAlpn = false
            httpProperties = HttpClientProperties.OptionalHttpProperties(
                connectionAcquisitionTimeout = connectionAcquisitionTimeout,
                maximumConnectionAge = environment.maxConnectionAge(),
                maximumConnections = maximumConnections,
                minimumConnections = minimumConnections,
                unresolvedProxyAddress = proxyAddress ?: systemHttpsProxyAddress(environment.host)
            )
            monitorConnectionHealth = ApnsEnvironment.PRODUCTION === environment &&
                httpProperties?.unresolvedProxyAddress != null
            concurrentRequestWatermark(low = DEFAULT_REQUESTS_WATER_MARK_LOW, high = DEFAULT_REQUESTS_WATER_MARK_HIGH)
            val keyPassword = requireNotNull(keyPassword) { "Private key is not set" }
            FileInputStream(p12File).use {
                it.pkcs12PrivateKeyEntries(keyPassword).first()
            }.apply {
                clientCredentials(privateKey, keyPassword, (certificate as X509Certificate).checkExpiration())
            }
        }, executor?.asCoroutineDispatcher())

        private fun X509Certificate.checkExpiration() = apply {
            logger.info("APNs certificate {} for {} expires {}", p12File.name, environment, notAfter.toInstant())
            if (willExpireWithin(0L.days)) {
                logger.error("APNs certificate {} for {} has expired", p12File.name, environment)
            } else if (willExpireWithin(7L.days)) {
                logger.warn("APNs certificate {} for {} will expire within 7 days", p12File.name, environment)
            }
        }
    }

    /**
     * @since 0.24.0
     */
    @ThreadSafe
    inner class HealthComponent internal constructor() {
        /**
         * Performs a connectivity health check on this APNs client. This check does not send a request.
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
}
