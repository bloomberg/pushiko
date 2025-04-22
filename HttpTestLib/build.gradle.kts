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

description = "Fixtures for testing HTTP request processing"

plugins {
    kotlin("jvm")
    application
    alias(libs.plugins.kover)
}

application {
    applicationDefaultJvmArgs += "-Dio.netty.leakDetection.level=paranoid"
    mainClass.set("com.bloomberg.pushiko.server.FakeHttp2ServerKt")
}

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(projects.pushikoApi)
    implementation(projects.pushikoCommons)
    implementation(projects.pushikoNettyKtx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.log4j.core)
    implementation(libs.netty.all)
    implementation(libs.slf4j.api)
}

kover {
    disable()
}
