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

import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.register

plugins {
    java apply false
    `maven-publish`
    signing
}

class LibraryConventionsPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = target.run {
        val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
        plugins.apply(libs.findPlugin("dokka").orElseThrow().get().pluginId)
    }
}
apply<LibraryConventionsPlugin>()

java {
    withSourcesJar()
}

fun MavenPom.commonInitialisation(project: Project) {
    val developerOrganization = "Bloomberg LP"
    ciManagement {
        name.set("Pushiko")
        url.set("https://github.com/bloomberg/pushiko/actions")
    }
    description = provider {
        requireNotNull(project.description) {
            "Project description is required for publishing"
        }
    }
    developers {
        developer {
            id.set("kennethshackleton")
            email.set("kshackleton1@bloomberg.net")
            name.set("Kenneth J. Shackleton")
            organization.set(developerOrganization)
            organizationUrl.set("https://github.com/bloomberg")
        }
        developer {
            id.set("xouabita")
            email.set("aabita@bloomberg.net")
            name.set("Alexandre Abita")
            organization.set(developerOrganization)
            organizationUrl.set("https://github.com/bloomberg")
        }
    }
    inceptionYear.set("2021")
    issueManagement {
        system.set("GitHub")
        url.set("https://github.com/bloomberg/pushiko/issues")
    }
    licenses {
        license {
            distribution.set("repo")
            name.set("The Apache Software License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        }
    }
    organization {
        name.set("Bloomberg LP")
    }
    packaging = "jar"
    scm {
        connection.set("https://github.com/bloomberg/pushiko.git")
        developerConnection.set("https://github.com/bloomberg/pushiko.git")
        tag.set(project.gitCommit().get())
        url.set("https://github.com/bloomberg/pushiko")
    }
    url.set("https://github.com/pages/bloomberg/pushiko/")
}

publishing {
    publications.register<MavenPublication>("main") {
        from(components.getByName("java"))
        pom { commonInitialisation(project) }
        artifact(tasks["dokkaHtmlJar"])
    }
}

signing {
    val signingKeyId: String? by project
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    publishing {
        isRequired = project.isRelease()
        publications.configureEach(::sign)
    }
}
