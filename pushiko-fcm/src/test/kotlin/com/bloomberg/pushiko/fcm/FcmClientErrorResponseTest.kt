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

import com.bloomberg.pushiko.fcm.model.ErrorCode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

internal class FcmClientErrorResponseTest {
    /**
     * Sample response from Firebase HTTP v1 looks like the following.
     * <pre>
     * {@code
     *   {
     *     "error": {
     *       "code": 400,
     *       "message": "Request contains an invalid argument.",
     *       "status": "INVALID_ARGUMENT",
     *       "details": [{
     *         "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
     *         "errorCode": "INVALID_ARGUMENT"
     *       }, {
     *         "@type": "type.googleapis.com/google.rpc.BadRequest",
     *         "fieldViolations": [{
     *           "field": "message.token",
     *           "description": "Invalid registration token"
     *         }]
     *       }]
     *     }
     *   }
     * }
     * </pre>
     */
    @Test
    fun invalidArgument() {
        Json.decodeFromString<FcmClientErrorResponse>(
"""{
  "error": {
    "code": 400,
    "message": "Request contains an invalid argument.",
    "status": "INVALID_ARGUMENT",
    "details": [{
      "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
      "errorCode": "INVALID_ARGUMENT"
    }, {
      "@type": "type.googleapis.com/google.rpc.BadRequest",
      "fieldViolations": [{
        "field": "message.token",
        "description": "Invalid registration token"
      }]
    }]
  }
}""".trimIndent()).error.apply {
            assertEquals(400, code)
            assertEquals("Request contains an invalid argument.", message)
            assertEquals("INVALID_ARGUMENT", status)
            assertEquals(2, details!!.size)
            val first = details!!.first()
            assertEquals("type.googleapis.com/google.firebase.fcm.v1.FcmError", first.type)
            assertSame(ErrorCode.INVALID_ARGUMENT, first.errorCode)
            val second = details!![1]
            assertEquals("type.googleapis.com/google.rpc.BadRequest", second.type)
            assertEquals(1, second.fieldViolations!!.size)
            val violation = second.fieldViolations!!.first()
            assertEquals("message.token", violation.field)
            assertEquals("Invalid registration token", violation.description)
        }
    }

    /**
     * Sample response from Firebase HTTP v1 looks like the following.
     * <pre>
     * {@code
     *   {
     *     "error": {
     *       "code": 400,
     *       "message": "Recipient of the message is not set.",
     *       "status": "INVALID_ARGUMENT",
     *       "details": [{
     *         "@type": "type.googleapis.com/google.rpc.BadRequest",
     *         "fieldViolations": [{
     *           "field": "message",
     *           "description": "Recipient of the message is not set."
     *         }]
     *       }, {
     *         "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
     *         "errorCode": "INVALID_ARGUMENT"
     *       }]
     *     }
     *   }
     * }
     * </pre>
     */
    @Test
    fun missingRegistrationToken() {
        Json.decodeFromString<FcmClientErrorResponse>(
"""
{
  "error": {
    "code": 400,
    "message": "Recipient of the message is not set.",
    "status": "INVALID_ARGUMENT",
    "details": [{
      "@type": "type.googleapis.com/google.rpc.BadRequest",
      "fieldViolations": [{
        "field": "message",
        "description": "Recipient of the message is not set."
      }]
    }, {
      "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
      "errorCode": "INVALID_ARGUMENT"
    }]
  }
}
""".trimIndent()).error.apply {
            assertEquals(400, code)
            assertEquals("Recipient of the message is not set.", message)
            assertEquals("INVALID_ARGUMENT", status)
            assertEquals(2, details!!.size)
            val first = details!!.first()
            assertEquals("type.googleapis.com/google.rpc.BadRequest", first.type)
            assertEquals(1, first.fieldViolations!!.size)
            val second = details!![1]
            assertEquals("type.googleapis.com/google.firebase.fcm.v1.FcmError", second.type)
            assertSame(ErrorCode.INVALID_ARGUMENT, second.errorCode)
            val violation = first.fieldViolations!!.first()
            assertEquals("message", violation.field)
            assertEquals("Recipient of the message is not set.", violation.description)
        }
    }

    /**
     * Sample response from Firebase HTTP v1 looks like the following.
     * <pre>
     * {@code
     *   {
     *     "error": {
     *       "code": 404,
     *       "message": "Requested entity was not found.",
     *       "status": "NOT_FOUND",
     *       "details": [{
     *         "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
     *         "errorCode": "UNREGISTERED"
     *       }]
     *     }
     *   }
     * }
     * </pre>
     */
    @Test
    fun unregistered() {
        Json.decodeFromString<FcmClientErrorResponse>(
"""{
  "error": {
    "code": 404,
    "message": "Requested entity was not found.",
    "status": "NOT_FOUND",
    "details": [{
      "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
      "errorCode": "UNREGISTERED"
    }]
  }
}""".trimIndent()).error.apply {
            assertEquals(404, code)
            assertEquals("Requested entity was not found.", message)
            assertEquals("NOT_FOUND", status)
            assertEquals(1, details!!.size)
            val detail = details!!.first()
            assertEquals("type.googleapis.com/google.firebase.fcm.v1.FcmError", detail.type)
            assertSame(ErrorCode.UNREGISTERED, detail.errorCode)
        }
    }
}
