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

package com.bloomberg.pushiko.fcm

import com.bloomberg.pushiko.fcm.model.Error
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection.HTTP_OK

sealed interface FcmResponse

@Serializable
data class FcmClientErrorResponse(
    @JvmField
    val error: Error
) : FcmResponse

data class FcmServerErrorResponse(
    @JvmField
    val request: FcmRequest,
    @JvmField
    val code: Int,
    @JvmField
    val body: String?,
    @JvmField
    val retryAfterMillis: Long? = null
) : FcmResponse

@Serializable
data class FcmSuccessResponse(
    @JvmField
    val name: String
) : FcmResponse {
    var code: Int = HTTP_OK
        @JvmSynthetic internal set
}
