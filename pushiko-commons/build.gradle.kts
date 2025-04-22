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

description = "Library of methods commonly used throughout Pushiko."

plugins {
    kotlin("jvm")
    alias(libs.plugins.dokka)
    `library-conventions`
    alias(libs.plugins.kover)
    alias(libs.plugins.pitest)
    alias(libs.plugins.detekt)
    alias(libs.plugins.android.lint)
}

dependencies {
    compileOnly(libs.findbugs.jsr305)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.slf4j.api)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testRuntimeOnly(libs.logback.classic)
}
