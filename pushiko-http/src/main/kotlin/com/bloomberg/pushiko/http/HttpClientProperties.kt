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

package com.bloomberg.pushiko.http

import com.bloomberg.pushiko.commons.slf4j.Logger
import com.bloomberg.pushiko.http.netty.DefaultHttpRetryPolicy
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private const val DEFAULT_MAX_CREATE_CHANNEL_RETRIES = 60
private const val DEFAULT_MAX_PENDING_ACQUISITIONS = 10_000
private const val DEFAULT_MAX_RETRIES_MULTIPLE = 3

data class HttpClientProperties internal constructor(
    override val connectionAcquisitionTimeout: Duration,
    override val connectionFuzzInterval: Duration,
    override val connectTimeout: Duration,
    override val defaultMaximumConcurrentStreams: Long,
    override val idleConnectionInterval: Duration,
    override val isMonitorConnections: Boolean,
    override val maximumConnectRetries: Int,
    override val maximumConnectionAge: Duration,
    override val maximumConnections: Int,
    override val maximumPendingAcquisitions: Int,
    override val maximumRequestRetries: Int,
    override val minimumConnections: Int,
    override val reaperDelay: Duration,
    override val retryPolicy: HttpRetryPolicy,
    override val summaryInterval: Duration,
    override val unresolvedProxyAddress: InetSocketAddress? = null,
    override val userTimeout: Duration
) : IHttpClientProperties {
    init {
        require(connectTimeout.inWholeSeconds > 0L) { "Connect timeout must be a positive number of seconds" }
        require(maximumConnectionAge.inWholeSeconds > 0L) {
            "Maximum connection age must be a positive number of seconds"
        }
        require(maximumConnectRetries >= 0) { "Maximum number of connect retries must be non-negative" }
        require(maximumConnections > 0) { "Maximum number of connections must be positive" }
        require(maximumPendingAcquisitions > 0) { "Maximum pending acquisitions must be positive" }
        require(minimumConnections <= maximumConnections) {
            "Minimum number of connections must not exceed the maximum number of connections"
        }
        unresolvedProxyAddress?.let {
            require(it.isUnresolved) { "Proxy address must not be resolved" }
        }
        require(userTimeout.inWholeSeconds > 0L) { "User timeout must be a positive number of seconds" }
    }

    companion object {
        val logger = Logger()

        @Suppress("CyclomaticComplexMethod", "FunctionName", "LongParameterList")
        fun OptionalHttpProperties(
            connectionAcquisitionTimeout: Duration? = null,
            connectionFuzzInterval: Duration? = null,
            connectTimeout: Duration? = null,
            defaultMaximumConcurrentStreams: Long? = null,
            idleConnectionInterval: Duration? = null,
            isMonitorConnections: Boolean? = null,
            maximumConnectionAge: Duration? = null,
            maximumConnectRetries: Int? = null,
            maximumConnections: Int? = null,
            maximumPendingAcquisitions: Int? = null,
            minimumConnections: Int? = null,
            reaperDelay: Duration? = null,
            retryPolicy: HttpRetryPolicy? = null,
            summaryInterval: Duration? = null,
            unresolvedProxyAddress: InetSocketAddress? = null
        ) = HttpClientProperties(
            connectionAcquisitionTimeout = connectionAcquisitionTimeout ?: 5L.minutes,
            connectionFuzzInterval = connectionFuzzInterval ?: 500L.milliseconds,
            connectTimeout = connectTimeout ?: 3L.seconds,
            defaultMaximumConcurrentStreams = defaultMaximumConcurrentStreams ?: 100L,
            idleConnectionInterval = idleConnectionInterval ?: 59L.toDuration(DurationUnit.MINUTES),
            isMonitorConnections = isMonitorConnections ?: false,
            maximumConnectionAge = maximumConnectionAge ?: 1L.hours,
            maximumConnections = maximumConnections ?: 1,
            maximumConnectRetries = maximumConnectRetries ?: DEFAULT_MAX_CREATE_CHANNEL_RETRIES,
            maximumPendingAcquisitions = maximumPendingAcquisitions ?: DEFAULT_MAX_PENDING_ACQUISITIONS,
            maximumRequestRetries = DEFAULT_MAX_RETRIES_MULTIPLE * (maximumConnections ?: 1),
            minimumConnections = minimumConnections ?: 0,
            reaperDelay = reaperDelay ?: 1L.minutes,
            retryPolicy = retryPolicy ?: DefaultHttpRetryPolicy,
            summaryInterval = summaryInterval ?: 5L.minutes,
            unresolvedProxyAddress = unresolvedProxyAddress,
            userTimeout = 10L.seconds
        )
    }
}

@Suppress("Detekt.ComplexInterface") // FIXME
interface IHttpClientProperties {
    val connectionAcquisitionTimeout: Duration
    val connectionFuzzInterval: Duration
    val connectTimeout: Duration
    val defaultMaximumConcurrentStreams: Long
    val idleConnectionInterval: Duration
    val isMonitorConnections: Boolean
    val maximumConnectionAge: Duration
    val maximumConnectRetries: Int
    val maximumConnections: Int
    val maximumPendingAcquisitions: Int
    val maximumRequestRetries: Int
    val minimumConnections: Int
    val reaperDelay: Duration
    val retryPolicy: HttpRetryPolicy
    val summaryInterval: Duration
    val unresolvedProxyAddress: InetSocketAddress?
    val userTimeout: Duration
}
