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
import java.security.KeyStore.Entry
import java.security.KeyStore.PasswordProtection
import java.security.KeyStore.PrivateKeyEntry

@Suppress("UNCHECKED_CAST")
@JvmSynthetic
internal fun InputStream.pkcs12PrivateKeyEntries(password: CharArray): Sequence<PrivateKeyEntry> = pkcs12KeyEntries(
    password
).filter {
    it is PrivateKeyEntry
} as Sequence<PrivateKeyEntry>

@JvmSynthetic
private fun InputStream.pkcs12KeyEntries(password: CharArray) = sequence<Entry> {
    val keyStore = KeyStore.getInstance("PKCS12").apply {
        load(this@pkcs12KeyEntries, password)
    }
    keyStore.aliases().asSequence().map { alias ->
        PasswordProtection(password).use {
            keyStore.getEntry(alias, it)
        }
    }.forEach {
        yield(it)
    }
}

private fun <R> PasswordProtection.use(block: (PasswordProtection) -> R): R = try {
    block(this)
} finally {
    destroy()
}
