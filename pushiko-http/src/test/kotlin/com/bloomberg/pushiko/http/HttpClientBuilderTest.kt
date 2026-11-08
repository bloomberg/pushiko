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

import io.netty.pkitesting.CertificateBuilder
import javax.net.ssl.X509KeyManager
import kotlin.test.Test
import kotlin.test.assertContentEquals

internal class HttpClientBuilderTest {
    @Test
    fun installsCompleteClientCertificateChain() {
        val root = CertificateBuilder()
            .subject("CN=Pushiko test root")
            .setIsCertificateAuthority(true)
            .buildSelfSigned()
        val intermediate = CertificateBuilder()
            .subject("CN=Pushiko test intermediate")
            .setIsCertificateAuthority(true)
            .buildIssuedBy(root)
        val client = CertificateBuilder()
            .subject("CN=Pushiko test client")
            .addExtendedKeyUsageClientAuth()
            .buildIssuedBy(intermediate)
        val keyManager = HttpClientBuilder()
            .clientCredentials(client.keyPair.private, PASSWORD, client.certificatePath.asList())
            .keyManagerFactory()
            .keyManagers
            .filterIsInstance<X509KeyManager>()
            .single()
        val alias = keyManager.getClientAliases(client.keyPair.private.algorithm, null).single()

        assertContentEquals(client.certificatePath, keyManager.getCertificateChain(alias))
    }

    private companion object {
        val PASSWORD = "changeit".toCharArray()
    }
}
