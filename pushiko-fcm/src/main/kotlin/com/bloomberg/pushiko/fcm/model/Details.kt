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

package com.bloomberg.pushiko.fcm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Details(
    /**
     * Type of error that has occurred.
     *
     * For an error code this might read: "type.googleapis.com/google.firebase.fcm.v1.FcmError"
     *
     * For a bad request this might instead read: "type.googleapis.com/google.rpc.BadRequest", with further details
     * provided among the [fieldViolations] if not null.
     */
    @JvmField
    @SerialName("@type") val type: String,
    @JvmField
    val errorCode: ErrorCode? = null,
    @JvmField
    val fieldViolations: List<FieldViolation>? = null
)
