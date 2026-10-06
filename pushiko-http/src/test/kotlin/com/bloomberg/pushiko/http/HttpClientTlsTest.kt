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

package com.bloomberg.pushiko.http

import com.bloomberg.pushiko.http.HttpClientProperties.Companion.OptionalHttpProperties
import com.bloomberg.pushiko.server.FakeHttp2Server
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE
import io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.SupportedCipherSuiteFilter
import io.netty.pkitesting.CertificateBuilder
import io.netty.pkitesting.X509Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

private val trustedAuthority = certificateAuthority("CN=Pushiko trusted test root")
private val untrustedAuthority = certificateAuthority("CN=Pushiko untrusted test root")

private fun certificateAuthority(subject: String): X509Bundle = CertificateBuilder()
    .subject(subject)
    .setIsCertificateAuthority(true)
    .buildSelfSigned()

private fun serverCertificate(issuer: X509Bundle, hostName: String): X509Bundle = CertificateBuilder()
    .subject("CN=$hostName")
    .addSanDnsName(hostName)
    .buildIssuedBy(issuer)

@Timeout(value = 30L, unit = TimeUnit.SECONDS)
internal class HttpClientTlsTest {
    @ParameterizedTest
    @EnumSource(SslProvider::class, names = ["JDK", "OPENSSL"])
    fun acceptsTrustedCertificateForTheHost(provider: SslProvider) = runTest {
        withClientOf(serverCertificate(trustedAuthority, "localhost"), provider) {
            send(okRequest()).use {
                assertEquals(200, it.code)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(SslProvider::class, names = ["JDK", "OPENSSL"])
    fun rejectsTrustedCertificateForAnotherHost(provider: SslProvider) = runTest {
        withClientOf(serverCertificate(trustedAuthority, "wrong.example"), provider) {
            assertFailsHandshake { send(okRequest()) }
        }
    }

    @ParameterizedTest
    @EnumSource(SslProvider::class, names = ["JDK", "OPENSSL"])
    fun rejectsCertificateFromAnUntrustedAuthority(provider: SslProvider) = runTest {
        withClientOf(serverCertificate(untrustedAuthority, "localhost"), provider) {
            assertFailsHandshake { send(okRequest()) }
        }
    }

    private fun okRequest() = HttpRequest {
        authority("localhost")
        path("/ok")
    }

    private suspend fun withClientOf(
        certificate: X509Bundle,
        provider: SslProvider,
        block: suspend HttpClient.() -> Unit
    ) {
        val server = FakeHttp2Server(certificate = certificate)
        val eventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
        try {
            server.start()
            val client = HttpClient(
                InetSocketAddress.createUnresolved("localhost", server.port),
                clientSslContext(provider),
                eventLoopGroup,
                properties = clientProperties
            )
            try {
                withContext(Dispatchers.Default) {
                    client.block()
                }
            } finally {
                client.close()
            }
        } finally {
            server.close()
            eventLoopGroup.shutdownGracefully(0L, 0L, TimeUnit.MILLISECONDS)
        }
    }

    // Pushiko's channel initializer must verify the host itself, so the context-level default that Netty 4.2
    // introduced is switched off here.
    private fun clientSslContext(provider: SslProvider) = SslContextBuilder.forClient()
        .sslProvider(provider)
        .endpointIdentificationAlgorithm(null)
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .trustManager(trustedAuthority.certificate)
        .applicationProtocolConfig(ApplicationProtocolConfig(ALPN, NO_ADVERTISE, ACCEPT, HTTP_2))
        .build()

    private suspend fun assertFailsHandshake(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull() ?: fail("Expected the TLS handshake to fail")
        assertTrue(generateSequence(failure) { it.cause }.any { it is SSLHandshakeException },
            "Expected an SSLHandshakeException in the cause chain of $failure")
    }

    private companion object {
        val clientProperties = OptionalHttpProperties().copy(
            connectTimeout = 3L.seconds,
            connectionAcquisitionTimeout = 10L.seconds,
            maximumConnectRetries = 0,
            maximumConnections = 1,
            maximumRequestRetries = 0,
            minimumConnections = 0
        )
    }
}
