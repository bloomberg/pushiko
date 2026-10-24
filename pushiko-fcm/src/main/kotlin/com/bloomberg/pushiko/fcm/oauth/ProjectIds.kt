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

package com.bloomberg.pushiko.fcm.oauth

private val projectIdPattern = Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")

internal fun String.requireValidProjectId(): String = apply {
    require(projectIdPattern.matches(this)) {
        "Google Cloud project ID must be 6 to 30 lowercase letters, digits, or hyphens; " +
            "it must start with a letter and end with a letter or digit"
    }
}
