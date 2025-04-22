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

import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.dokka.gradle.DokkaTaskPartial
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

description = "Library for sending HTTP requests."

plugins {
    kotlin("jvm")
    kotlin("kapt")
    alias(libs.plugins.dokka)
    id("bb-jmh")
    `library-conventions`
    alias(libs.plugins.kover)
    alias(libs.plugins.pitest)
    alias(libs.plugins.detekt)
    alias(libs.plugins.android.lint)
}

apply {
    plugin("kotlinx-atomicfu")
}

disableKotlinCompilerAssertions()

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}
extensions.getByType<KotlinJvmProjectExtension>().target.compilations.let {
    it["integrationTest"].associateWith(it["main"])
    it["jmh"].associateWith(it["main"])
}

dependencies {
    compileOnly(libs.findbugs.jsr305)
    implementation(platform(libs.netty.bom))
    implementation(projects.pushikoCommons)
    implementation(projects.pushikoHealth)
    implementation(projects.pushikoNettyKtx)
    implementation(projects.pushikoPools)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.netty)
    implementation(libs.slf4j.api)
    testImplementation(projects.httpTestLib)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testRuntimeOnly(libs.logback.classic)
    // See: https://netty.io/wiki/forked-tomcat-native.html
    listOf(
        "linux-x86_64",
        "osx-aarch_64",
        "osx-x86_64"
    ).forEach {
        // TODO Replace with:
        // testRuntimeOnly(netty("tcnative", version = "", classifier = it))
        testRuntimeOnly("${libs.netty.tcnative.boringssl.get()}::$it")
    }
    testRuntimeOnly("${libs.netty.transport.native.epoll.get()}::linux-x86_64")
    testRuntimeOnly("${libs.netty.transport.native.kqueue.get()}::osx-aarch_64")
    testRuntimeOnly("${libs.netty.transport.native.kqueue.get()}::osx-x86_64")
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    maxHeapSize = "128m"
    jvmArgs("-XX:+HeapDumpOnOutOfMemoryError")
    testLogging {
        events(TestLogEvent.STANDARD_OUT, TestLogEvent.STANDARD_ERROR)
    }
    shouldRunAfter("test")
}

tasks.withType<DokkaTask>().configureEach {
    dependsOn("kaptKotlin") // FIXME Remove?
}

tasks.withType<DokkaTaskPartial>().configureEach {
    dependsOn("kaptKotlin") // FIXME Remove?
}
