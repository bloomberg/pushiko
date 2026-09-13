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

package com.bloomberg.pushiko.apns.keys

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.io.File
import java.io.IOException
import java.security.AlgorithmParameters
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.annotation.concurrent.ThreadSafe

private const val MAXIMUM_PKCS8_FILE_SIZE_BYTES = 16 * 1_024
private const val APPLE_IDENTIFIER_LENGTH = 10
private val APPLE_IDENTIFIER = Regex("[A-Z0-9]{$APPLE_IDENTIFIER_LENGTH}")
private val PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----".toByteArray(Charsets.US_ASCII)
private val PKCS8_FOOTER = "-----END PRIVATE KEY-----".toByteArray(Charsets.US_ASCII)

/**
 * An Apple-issued private key used to sign APNs provider authentication tokens.
 *
 * @property keyId the ten-character identifier Apple assigned to the key.
 * @property teamId the ten-character identifier Apple assigned to the developer team.
 */
@ThreadSafe
public class ApnsSigningKey(
    public val keyId: String,
    public val teamId: String,
    private val privateKey: ECPrivateKey
) {
    private val signer: ECDSASigner

    init {
        require(APPLE_IDENTIFIER.matches(keyId)) {
            "keyId must be a ten-character uppercase alphanumeric Apple key identifier"
        }
        require(APPLE_IDENTIFIER.matches(teamId)) {
            "teamId must be a ten-character uppercase alphanumeric Apple team identifier"
        }
        if (!privateKey.params.matches(P256_PARAMETERS)) {
            throw InvalidKeyException("APNs signing key must use the P-256 elliptic curve")
        }
        Signature.getInstance(SIGNATURE_ALGORITHM).initSign(privateKey)
        signer = try {
            ECDSASigner(privateKey)
        } catch (exception: JOSEException) {
            throw InvalidKeyException("APNs signing key must support ES256", exception)
        }
    }

    @JvmSynthetic
    internal fun createProviderToken(issuedAt: Instant): String {
        val token = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType.JWT)
                .keyID(keyId)
                .build(),
            JWTClaimsSet.Builder()
                .issuer(teamId)
                .issueTime(Date.from(issuedAt))
                .build()
        )
        return try {
            token.sign(signer)
            token.serialize()
        } catch (exception: JOSEException) {
            throw IllegalStateException("Could not sign APNs provider authentication token", exception)
        }
    }

    public companion object {
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        private val P256_PARAMETERS: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }

        /**
         * Loads an Apple APNs signing key from an unencrypted PKCS#8 PEM file.
         *
         * @param pkcs8File the `.p8` file downloaded from Apple.
         * @param keyId the ten-character identifier Apple assigned to the key.
         * @param teamId the ten-character identifier Apple assigned to the developer team.
         */
        @JvmStatic
        @Throws(IOException::class, InvalidKeyException::class)
        public fun loadFromPkcs8File(
            pkcs8File: File,
            keyId: String,
            teamId: String
        ): ApnsSigningKey {
            val keyBytes = pkcs8File.readPkcs8KeyBytes()
            return try {
                ApnsSigningKey(keyId, teamId, keyBytes.toEcPrivateKey())
            } finally {
                keyBytes.fill(0)
            }
        }

        private fun File.readPkcs8KeyBytes(): ByteArray {
            val pemBytes = inputStream().use { it.readNBytes(MAXIMUM_PKCS8_FILE_SIZE_BYTES + 1) }
            if (pemBytes.isEmpty() || pemBytes.size > MAXIMUM_PKCS8_FILE_SIZE_BYTES) {
                throw IOException("APNs PKCS#8 signing key file must contain 1 to $MAXIMUM_PKCS8_FILE_SIZE_BYTES bytes")
            }
            return try {
                pemBytes.decodePkcs8Pem()
            } finally {
                pemBytes.fill(0)
            }
        }

        private fun ByteArray.decodePkcs8Pem(): ByteArray {
            var offset = skipAsciiWhitespace(0)
            if (!matchesAt(PKCS8_HEADER, offset)) {
                malformedPem()
            }
            offset += PKCS8_HEADER.size
            val footerOffset = indexOf(PKCS8_FOOTER, offset)
            if (footerOffset < 0) {
                malformedPem()
            }

            val base64Bytes = ByteArray(footerOffset - offset)
            var base64Size = 0
            for (index in offset until footerOffset) {
                if (!this[index].isAsciiWhitespace()) {
                    base64Bytes[base64Size++] = this[index]
                }
            }
            if (base64Size == 0) {
                malformedPem()
            }

            offset = skipAsciiWhitespace(footerOffset + PKCS8_FOOTER.size)
            if (offset != size) {
                malformedPem()
            }

            val encoded = base64Bytes.copyOf(base64Size)
            return try {
                Base64.getDecoder().decode(encoded)
            } catch (exception: IllegalArgumentException) {
                throw IOException("APNs PKCS#8 signing key contains invalid Base64", exception)
            } finally {
                base64Bytes.fill(0)
                encoded.fill(0)
            }
        }

        private fun ByteArray.skipAsciiWhitespace(start: Int): Int {
            var index = start
            while (index < size && this[index].isAsciiWhitespace()) {
                index++
            }
            return index
        }

        private fun ByteArray.matchesAt(expected: ByteArray, offset: Int): Boolean =
            offset >= 0 && offset + expected.size <= size && expected.indices.all { expected[it] == this[offset + it] }

        private fun ByteArray.indexOf(expected: ByteArray, start: Int): Int {
            for (offset in start..size - expected.size) {
                if (matchesAt(expected, offset)) {
                    return offset
                }
            }
            return -1
        }

        private fun Byte.isAsciiWhitespace(): Boolean = this == ' '.code.toByte() ||
            this == '\t'.code.toByte() ||
            this == '\r'.code.toByte() ||
            this == '\n'.code.toByte()

        private fun malformedPem(): Nothing = throw IOException(
            "Could not find exactly one PKCS#8 private key PEM block"
        )

        private fun ByteArray.toEcPrivateKey(): ECPrivateKey {
            val privateKey = try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(this))
            } catch (exception: InvalidKeySpecException) {
                throw InvalidKeyException("Invalid APNs PKCS#8 EC private key", exception)
            }
            if (privateKey !is ECPrivateKey) {
                throw InvalidKeyException("APNs signing key must be an EC private key")
            }
            return privateKey
        }

        private fun ECParameterSpec.matches(other: ECParameterSpec): Boolean = curve == other.curve &&
            generator == other.generator &&
            order == other.order &&
            cofactor == other.cofactor
    }
}
