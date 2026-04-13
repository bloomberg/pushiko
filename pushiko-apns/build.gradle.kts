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

import java.net.URI
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.dokka.gradle.DokkaTaskPartial
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

description = "Pushiko library for sending Apple Push Notifications with APNs."

plugins {
    kotlin("jvm")
    kotlin("kapt")
    alias(libs.plugins.serialization)
    alias(libs.plugins.dokka)
    id("bb-jmh")
    `library-conventions`
    alias(libs.plugins.kover)
    alias(libs.plugins.pitest)
    alias(libs.plugins.detekt)
    alias(libs.plugins.android.lint)
}

dependencies {
    api(projects.pushikoApi)
    api(projects.pushikoJson)
    compileOnly(libs.findbugs.jsr305)
    implementation(projects.pushikoCommons)
    implementation(projects.pushikoHealth)
    implementation(projects.pushikoHttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)
    implementation(libs.slf4j.api)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.gson)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
}

testing {
    suites {
        @Suppress("UnstableApiUsage")
        register<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(project())
                implementation(libs.kotlin.test.junit5)
                implementation(libs.kotlinx.coroutines.core)
                runtimeOnly(libs.logback.classic)
            }
            targets {
                all {
                    testTask.configure {
                        group = "verification"
                        maxHeapSize = "128m"
                        jvmArgs("-XX:+HeapDumpOnOutOfMemoryError")
                        System.getProperties().filter {
                            it.key.toString().startsWith("http")
                        }.forEach {
                            jvmArgs("-D${it.key}=${it.value}")
                        }
                        testLogging {
                            events(TestLogEvent.STANDARD_OUT, TestLogEvent.STANDARD_ERROR)
                        }
                        shouldRunAfter(tasks.named("test"))
                    }
                }
            }
        }
    }
}

extensions.getByType<KotlinJvmProjectExtension>().target.compilations.let { compilations ->
    compilations["integrationTest"].associateWith(compilations["main"])
}

tasks.withType<DokkaTask>().configureEach {
    dependsOn("kaptKotlin") // FIXME Remove?
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            remoteUrl.set(URI.create("https://github.com/bloomberg/pushiko/tree/main/pushiko-apns/src").toURL())
            remoteLineSuffix.set("#L")
        }
    }
}

tasks.withType<DokkaTaskPartial>().configureEach {
    dependsOn("kaptKotlin") // FIXME Remove?
}
