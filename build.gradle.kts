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

import info.solidsoft.gradle.pitest.PitestPlugin
import info.solidsoft.gradle.pitest.PitestPluginExtension
import io.gitlab.arturbosch.detekt.DetektPlugin
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import java.net.URL
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.gradle.DokkaPlugin
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.gradle.ext.copyright
import org.jetbrains.gradle.ext.settings
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import java.time.Duration

buildscript {
    dependencies {
        classpath(libs.atomicfu)
    }
}

plugins {
    base
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
    alias(libs.plugins.pitest) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dependencyCheck) apply false
    alias(libs.plugins.ideaExt)
    alias(libs.plugins.nexus.publish)
}

group = "com.bloomberg.pushiko"
version = pushikoVersion
logger.quiet("Group: {}; Version: {}", group, version)

nexusPublishing {
    repositories {
        sonatype()
    }
    transitionCheckOptions {
        maxRetries.set(180)
        delayBetween.set(Duration.ofSeconds(10L))
    }
}

dependencies {
    kover(projects.pushikoApi)
    kover(projects.pushikoApns)
    kover(projects.pushikoFcm)
    kover(projects.pushikoCommons)
    kover(projects.pushikoHealth)
    kover(projects.pushikoHttp)
    kover(projects.pushikoJson)
    kover(projects.pushikoMetrics)
    kover(projects.pushikoNettyKtx)
    kover(projects.pushikoPools)
}

subprojects {
    group = rootProject.group
    version = rootProject.version
    apply {
        plugin("org.jlleitschuh.gradle.ktlint")
        plugin("org.owasp.dependencycheck")
    }
    plugins.withType<JavaPlugin>().configureEach {
        configure<JavaPluginExtension> {
            withSourcesJar()
        }
        dependencies {
            "implementation"(platform(libs.kotlinx.coroutines.bom))
            constraints.add("runtimeOnly", libs.netty.all) {
                version {
                    reject("[0, 4.1.85.Final]")
                    because("CVE-2022-41881 Stack exhaustion; CVE-2022-41915 HTTP Response splitting")
                }
            }
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        targetCompatibility = JavaVersion.VERSION_11.toString()
    }
    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions {
            allWarningsAsErrors = true
            freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
            jvmTarget = "11"
        }
    }
    tasks.withType<Jar>().configureEach {
        manifest {
            attributes(mapOf("Bundle-License" to "Apache-2.0;link=\"https://www.apache.org/licenses/LICENSE-2.0\""))
        }
        metaInf {
            from("$rootDir/LICENSE")
        }
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
            exceptionFormat = TestExceptionFormat.FULL
            showCauses = true
            showExceptions = true
            showStackTraces = true
        }
        useJUnitPlatform()
        mapOf(
            "junit.jupiter.execution.timeout.lifecycle.method.default" to "60s",
            "junit.jupiter.execution.timeout.mode" to "disabled_on_debug",
            "junit.jupiter.execution.timeout.testable.method.default" to "60s"
        ).forEach {
            systemProperty(it.key, it.value)
        }
        jvmArgs("-Dio.netty.leakDetection.level=paranoid")
    }
    plugins.withType<PitestPlugin> {
        configure<PitestPluginExtension> {
            junit5PluginVersion.set("1.1.0")
            targetClasses.set(listOf("com.bloomberg.*"))
        }
    }
    plugins.withType<DetektPlugin> {
        configure<DetektExtension> {
            source.from(files("src"))
            config.from(files("${rootProject.projectDir}/config/detekt/config.yml"))
            buildUponDefaultConfig = true
            parallel = false
            debug = false
            ignoreFailures = false
        }
    }
    plugins.withType<DokkaPlugin> {
        tasks.register<Jar>("dokkaHtmlJar") {
            dependsOn(tasks.dokkaHtml)
            from(tasks.dokkaHtml.flatMap { it.outputDirectory })
            archiveClassifier.set("javadoc")
        }
    }
    tasks.withType<DokkaTask>().configureEach {
        moduleName.set("Pushiko")
        dokkaSourceSets.named("main") {
            sourceLink {
                remoteUrl.set(URL("https://github.com/bloomberg/pushiko/tree/main/${project.name}/src"))
                localDirectory.set(projectDir.resolve("src"))
                remoteLineSuffix.set("#L")
            }
            perPackageOption {
                documentedVisibilities.addAll(
                    DokkaConfiguration.Visibility.INTERNAL,
                    DokkaConfiguration.Visibility.PUBLIC
                )
            }
            includeNonPublic.set(false)
            jdkVersion.set(JavaVersion.VERSION_11.majorVersion.toInt())
            noJdkLink.set(false)
            noStdlibLink.set(false)
            if (project.file("Module.md").exists()) {
                includes.from(project.file("Module.md"))
            }
        }
    }
}

allprojects {
    ktlint {
        disabledRules.set(setOf("import-ordering", "indent", "wrapping")) // FIXME ?
        reporters {
            reporter(ReporterType.HTML)
        }
    }
    tasks.withType<GenerateReportsTask>().configureEach {
        reportsOutputDirectory.set(rootProject.layout.buildDirectory.dir("reports/ktlint/${project.name}/$name"))
    }
}

koverReport {
    filters {
        excludes {
            classes(
                "io.netty.*",
                "*.jmh_generated.*",
                "*\$\$*"
            )
        }
    }
    defaults {
        verify {
            rule("Minimal coverage") {
                bound {
                    minValue = 80
                    aggregation = AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}

idea.project.settings {
    copyright {
        useDefault = "Bloomberg"
        profiles {
            create("Bloomberg") {
                notice = """
Copyright ${'$'}today.year Bloomberg Finance L.P.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
""".trimIndent()
            }
        }
    }
}
