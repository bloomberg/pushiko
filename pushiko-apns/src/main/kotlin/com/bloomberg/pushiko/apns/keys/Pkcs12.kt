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

package com.bloomberg.pushiko.apns.keys

import java.io.InputStream
import java.security.KeyStore
import java.security.KeyStore.PasswordProtection
import java.security.KeyStore.PrivateKeyEntry
import java.security.cert.X509Certificate

@JvmSynthetic
internal fun InputStream.pkcs12PrivateKeyEntry(password: CharArray): PrivateKeyEntry {
    val keyStore = KeyStore.getInstance("PKCS12").apply {
        load(this@pkcs12PrivateKeyEntry, password)
    }
    var privateKeyAlias: String? = null
    var privateKeyEntryCount = 0
    keyStore.aliases().asSequence().forEach { alias ->
        if (keyStore.entryInstanceOf(alias, PrivateKeyEntry::class.java)) {
            privateKeyAlias = privateKeyAlias ?: alias
            privateKeyEntryCount++
        }
    }
    require(privateKeyEntryCount == 1) {
        "PKCS#12 must contain exactly one private key entry; found $privateKeyEntryCount"
    }
    return PasswordProtection(password).use {
        keyStore.getEntry(checkNotNull(privateKeyAlias), it) as PrivateKeyEntry
    }
}

@JvmSynthetic
internal fun PrivateKeyEntry.x509CertificateChain(): List<X509Certificate> = certificateChain.mapIndexed {
    index, certificate ->
    require(certificate is X509Certificate) {
        "PKCS#12 certificate chain entry $index must be an X.509 certificate"
    }
    certificate
}

private fun <R> PasswordProtection.use(block: (PasswordProtection) -> R): R = try {
    block(this)
} finally {
    destroy()
}
