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
import com.bloomberg.pushiko.http.HttpClientProperties.Companion.OptionalHttpProperties
import com.bloomberg.pushiko.http.netty.PushikoHttp2FrameLogger
import com.bloomberg.pushiko.http.netty.EventLoopGroups.sharedEventLoopGroup
import com.bloomberg.pushiko.http.netty.EventLoopGroups.sharedSingleEventLoopGroup
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE
import io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2
import io.netty.handler.ssl.OpenSsl
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.SupportedCipherSuiteFilter
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.net.InetSocketAddress
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.annotation.concurrent.NotThreadSafe
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
fun HttpClient(block: HttpClientBuilder.() -> Unit): HttpClient {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return HttpClientBuilder().apply(block).build()
}

private val httpLogger = LoggerFactory.getLogger(HttpClient::class.java)

enum class EventLoopGroupType {
    PRIMARY,
    SECONDARY
}

@NotThreadSafe
class HttpClientBuilder internal constructor() {
    lateinit var host: CharSequence
    var port: Int = 443
    var requiresAlpn = true
    var httpProperties: HttpClientProperties? = null
    var monitorConnectionHealth: Boolean = false
    var eventLoopGroupType: EventLoopGroupType? = null

    private var privateKey: PrivateKey? = null
    private var privateKeyPassword: CharArray? = null
    private var clientCertificate: X509Certificate? = null

    private var concurrentRequestWaterMark: ConcurrentRequestWaterMark = defaultWatermark

    private val sslContext: SslContext by lazy(LazyThreadSafetyMode.NONE) {
        SslContextBuilder.forClient()
            .sslProvider(sslProvider)
            .apply {
                if (clientCertificate != null) {
                    keyManager(privateKey!!, privateKeyPassword!!.joinToString(""), clientCertificate)
                }
            }
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .apply {
                if (requiresAlpn) {
                    applicationProtocolConfig(ApplicationProtocolConfig(ALPN, NO_ADVERTISE, ACCEPT, HTTP_2))
                }
            }
            .build()
    }

    fun clientCredentials(
        key: PrivateKey,
        keyPassword: CharArray,
        clientCertificate: X509Certificate
    ) = apply {
        privateKey = key
        privateKeyPassword = keyPassword
        this.clientCertificate = clientCertificate
    }

    fun concurrentRequestWatermark(low: Long, high: Long) = apply {
        concurrentRequestWaterMark = ConcurrentRequestWaterMark(low, high)
    }

    @JvmSynthetic
    internal fun build() = HttpClient(
        InetSocketAddress.createUnresolved(host.toString(), port),
        sslContext,
        eventLoopGroupType?.eventLoopGroup() ?: sharedEventLoopGroup,
        httpProperties ?: OptionalHttpProperties(),
        PushikoHttp2FrameLogger(httpLogger, Level.DEBUG)
    )

    private fun EventLoopGroupType.eventLoopGroup() = when (this) {
        EventLoopGroupType.PRIMARY -> sharedEventLoopGroup
        EventLoopGroupType.SECONDARY -> sharedSingleEventLoopGroup
    }

    private companion object {
        private val logger = Logger()
        val sslProvider: SslProvider = if (OpenSsl.isAvailable()) {
            logger.info("Native SSL provider is available; will use native provider")
            SslProvider.OPENSSL
        } else {
            logger.info("Native SSL provider not available; will use JDK SSL provider")
            SslProvider.JDK
        }
    }
}
