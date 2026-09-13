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

import org.cyclonedx.model.ExternalReference
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.from
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

private const val CYCLONEDX_DIRECT_TASK = "cyclonedxDirectBom"
private const val FINAL_SBOM_TASK = "cyclonedxFinalBom"

class PushikoSbomConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("org.cyclonedx.bom")

        val publishedSbom = layout.buildDirectory.file(
            "reports/cyclonedx-direct/${project.name}-${project.version}-cyclonedx.json"
        )
        val directSbom = tasks.named(CYCLONEDX_DIRECT_TASK) {
            setCycloneDxProperty(this, "IncludeConfigs", listOf("runtimeClasspath"))
            setCycloneDxProperty(this, "TestConfigs", emptyList<String>())
            setCycloneDxProperty(this, "IncludeBomSerialNumber", false)
            setCycloneDxProperty(this, "IncludeBuildSystem", false)
            setCycloneDxProperty(this, "ExternalReferences", listOf(ExternalReference().apply {
                type = ExternalReference.Type.VCS
                url = "https://github.com/bloomberg/pushiko.git"
            }))
            invokeCycloneDxMethod(this, "getXmlOutput", "unsetConvention")
            setCycloneDxProperty(this, "JsonOutput", publishedSbom)
        }

        tasks.register(FINAL_SBOM_TASK) {
            group = "reporting"
            description = "Generates the final CycloneDX SBOM for this published artifact."
            dependsOn(directSbom)
        }

        plugins.withId("maven-publish") {
            extensions.configure<PublishingExtension> {
                publications.withType<MavenPublication>().configureEach {
                    artifact(publishedSbom) {
                        classifier = "cyclonedx"
                        extension = "json"
                        builtBy(directSbom)
                    }
                }
            }
        }

        plugins.withId("java") {
            val sbomFileName = "${project.name}.cdx.json"
            val runtimeJars = tasks.withType<Jar>().matching { it.name == "jar" }
            val verifyEmbeddedSbom = tasks.register<VerifyEmbeddedSbom>("verifyEmbeddedSbom") {
                group = "verification"
                description = "Verifies that the runtime JAR embeds its published CycloneDX SBOM."
                sbom.set(publishedSbom)
                entryName.set("META-INF/sbom/$sbomFileName")
            }
            runtimeJars.all {
                dependsOn(directSbom)
                from(publishedSbom) {
                    into("META-INF/sbom")
                    rename { sbomFileName }
                }
            }
            verifyEmbeddedSbom.configure {
                dependsOn(runtimeJars)
                archives.from(runtimeJars)
            }
            tasks.named("check").configure { dependsOn(verifyEmbeddedSbom) }
        }
    }
}

private fun setCycloneDxProperty(target: Any, property: String, value: Any) {
    val setter = target.javaClass.methods.firstOrNull { it.name == "set$property" && it.parameterCount == 1 }
        ?: error("Missing set$property on ${target.javaClass.name}")
    setter.invoke(target, value)
}

private fun invokeCycloneDxMethod(target: Any, getter: String, method: String, vararg args: Any) {
    val getterMethod = target.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
        ?: error("Missing $getter on ${target.javaClass.name}")
    val property = getterMethod.invoke(target)
    val targetMethod = property.javaClass.methods.firstOrNull {
        it.name == method && it.parameterCount == args.size
    } ?: error("Missing $method on ${property.javaClass.name}")
    targetMethod.invoke(property, *args)
}
