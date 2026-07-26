import me.champeau.gradle.japicmp.JapicmpTask
import me.champeau.gradle.japicmp.JApiCmpWorkerAction
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.FileNotFoundException
import java.net.URI

plugins {
    java
}

val groupPath = project.group.toString().replace('.', '/')

val baselineVersion = project.findProperty("japicmpBaseline")?.toString() ?: run {
    val currentVersion = project.version.toString()
    val currentMajor = currentVersion.substringBefore('.')
    val metadataUrl = "https://repo1.maven.org/maven2/$groupPath/${project.name}/maven-metadata.xml"
    val metadata = try {
        URI.create(metadataUrl).toURL().openStream().bufferedReader().use { it.readText() }
    } catch (_: FileNotFoundException) {
        return@run ""
    }
    val allVersions = """<version>([^<]+)</version>""".toRegex().findAll(metadata)
        .map { it.groupValues[1] }
        .filter { it.startsWith("$currentMajor.") }
        .toList()

    allVersions.maxWithOrNull { first, second ->
        val firstComponents = first.drop(currentMajor.length + 1).split('.')
        val secondComponents = second.drop(currentMajor.length + 1).split('.')
        for (i in 0 until minOf(firstComponents.size, secondComponents.size)) {
            val firstVersion = firstComponents[i].toInt()
            val secondVersion = secondComponents[i].toInt()
            firstVersion.compareTo(secondVersion).let {
                if (it != 0) {
                    return@maxWithOrNull it
                }
            }
        }
        firstComponents.size.compareTo(secondComponents.size)
    } ?: ""
}

val baselineJar = layout.buildDirectory.file(project.provider {
    "japicmp/${project.name}-$baselineVersion.jar"
})

val downloadJapicmpBaseline = tasks.register("downloadJapicmpBaseline") {
    group = "verification"
    description = "Download baseline JAR from Maven Central for japicmp comparison."
    onlyIf { baselineVersion.isNotEmpty() }
    outputs.file(baselineJar)
    notCompatibleWithConfigurationCache("downloads from Maven Central")

    doLast {
        val downloadUrl = "https://repo1.maven.org/maven2/$groupPath/${project.name}/$baselineVersion/${project.name}-$baselineVersion.jar"
        val dest = baselineJar.get().asFile
        if (!dest.exists()) {
            dest.parentFile.mkdirs()
            runCatching {
                logger.info("Downloading baseline JAR from {}", downloadUrl)
                URI.create(downloadUrl).toURL().openStream().use {
                    dest.outputStream().use(it::copyTo)
                }
                logger.info("Baseline JAR downloaded: {}", dest.absolutePath)
            }.onFailure {
                logger.info("Failed to download baseline JAR ({}), skipping check", it.message)
                dest.writeText("")
            }
        }
    }
}

val jarTask = tasks.named<Jar>("jar")

val checkSemver = tasks.register<JapicmpTask>("checkSemver") {
    dependsOn(downloadJapicmpBaseline, jarTask)
    group = "verification"
    description = "Fails on binary-incompatible changes vs $baselineVersion."
    onlyIf { baselineJar.get().asFile.exists() && baselineJar.get().asFile.length() > 0 }

    oldArchiveList.add(project.provider {
        JApiCmpWorkerAction.Archive(baselineJar.get().asFile, baselineVersion)
    })
    newArchiveList.add(project.provider {
        JApiCmpWorkerAction.Archive(jarTask.get().archiveFile.get().asFile, project.version.toString())
    })

    oldClasspath.from(configurations.runtimeClasspath)
    newClasspath.from(configurations.runtimeClasspath)

    onlyBinaryIncompatibleModified = true
    failOnModification = true
    ignoreMissingClasses = true

    // Filter Kotlin bytecode idioms that are JVM-public but not part of the API.
    methodExcludes = listOf("*#*\$*(*)")
    fieldExcludes = listOf("*#*\$*")
    classExcludes = listOf("*\$WhenMappings")

    txtOutputFile = layout.buildDirectory.file("reports/japicmp/${project.name}.txt")
    htmlOutputFile = layout.buildDirectory.file("reports/japicmp/${project.name}.html")
}

rootProject.tasks.named("checkSemver") { dependsOn(checkSemver) }
