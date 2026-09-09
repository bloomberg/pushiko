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

description = "Library for working with JSON with Pushiko."

plugins {
    kotlin("jvm")
    alias(libs.plugins.dokka)
    `library-conventions`
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.android.lint)
}

disableKotlinCompilerAssertions()

dependencies {
    compileOnly(libs.findbugs.jsr305)
    implementation(projects.pushikoCommons)
    implementation(libs.moshi)
    testImplementation(libs.jazzer.junit)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.gson)
}

val jvmFuzzProfile = providers.gradleProperty("pushiko.fuzz.profile").getOrElse("smoke")
val jvmFuzzDuration = when (jvmFuzzProfile) {
    "smoke" -> "5s"
    "release" -> "1m"
    "scheduled" -> "5m"
    else -> error("Unsupported JVM fuzzing profile: $jvmFuzzProfile")
}

tasks.register<Test>("jvmFuzzJsonObjectWriter") {
    description = "Runs the JsonObjectWriter Jazzer campaign."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    environment("JAZZER_FUZZ", "1")
    filter {
        includeTestsMatching("com.bloomberg.pushiko.json.JsonObjectWriterFuzzTest.fuzzStringValue")
    }
    systemProperty("jazzer.instrument", "com.bloomberg.pushiko.json.**")
    systemProperty("jazzer.max_duration", jvmFuzzDuration)
    systemProperty("jazzer.reproducer_path", layout.buildDirectory.get().asFile.absolutePath)
    systemProperty("junit.jupiter.execution.parallel.enabled", false)
    maxHeapSize = "1g"
    outputs.upToDateWhen { false }
    workingDir(layout.buildDirectory.get().asFile)
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("*FuzzTest*")
    }
}

tasks.register("jvmFuzz") {
    description = "Runs JSON JVM fuzzing."
    group = "verification"
    dependsOn("jvmFuzzJsonObjectWriter")
}

kover {
    excludeTests {
        tasks("jvmFuzzJsonObjectWriter")
    }
}
