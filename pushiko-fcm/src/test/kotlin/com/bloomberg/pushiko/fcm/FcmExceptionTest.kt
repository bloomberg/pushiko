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

import com.bloomberg.pushiko.fcm.exceptions.FcmException
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertNull
import kotlin.test.assertSame

internal class FcmExceptionTest {
    @Test
    fun message() {
        val message = "Uh-oh!"
        FcmException(message = message).apply {
            assertSame(message, this.message)
            assertNull(cause)
        }
    }

    @Test
    fun cause() {
        val cause = IOException("Uh-oh!")
        FcmException(cause = cause).apply {
            assertNull(message)
            assertSame(cause, this.cause)
        }
    }
}
