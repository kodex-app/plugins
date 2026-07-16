import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.jar.JarFile

plugins {
    java
}

allprojects {
    group = "dev.kodex"
    version = "1.0.0"

    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}

val devPlugins =
    configurations.create("devPlugins") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

val pluginHostLibs =
    configurations.create("pluginHostLibs") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies {
    subprojects.forEach { devPlugins(project(it.path)) }

    pluginHostLibs(libs.kodex.spi)
    pluginHostLibs(libs.pf4j)
    pluginHostLibs(libs.jsoup)
    pluginHostLibs(libs.okhttp)
    pluginHostLibs(libs.jackson.databind)
    pluginHostLibs(libs.slf4j.api)
}

fun writePluginsRepo(
    jars: List<File>,
    outDir: File,
    defaultVersion: String,
    baseUrl: String?,
    sourcesByJar: Map<String, Any?>,
    logger: Logger,
) {
    // The whole directory is published as-is, so start clean — stale JARs from a previous layout
    // must not ride along.
    outDir.deleteRecursively()
    outDir.mkdirs()
    val entries =
        jars.filter { it.extension == "jar" }.sortedBy { it.name }.mapNotNull { jar ->
            val manifest = JarFile(jar).use { it.manifest }
            val id = manifest?.mainAttributes?.getValue("Plugin-Id")
            if (id == null) {
                logger.warn("Skipping ${jar.name}: no Plugin-Id manifest attribute (not a PF4J plugin JAR).")
                return@mapNotNull null
            }
            val attrs = manifest.mainAttributes
            val name = attrs.getValue("Plugin-Name") ?: id
            val pluginVersion = attrs.getValue("Plugin-Version") ?: defaultVersion
            val provider = attrs.getValue("Plugin-Provider") ?: "Kodex"
            val requires = attrs.getValue("Plugin-Requires")
            val kind =
                when {
                    jar.name.startsWith("content-") -> "CONTENT"
                    jar.name.startsWith("metadata-") -> "METADATA"
                    else -> "OTHER"
                }
            val sha512 =
                MessageDigest
                    .getInstance("SHA-512")
                    .digest(jar.readBytes())
                    .joinToString("") { "%02x".format(it) }
            // JARs are filed into a per-kind folder (content/ metadata/ other/); the release url keeps
            // the same relative path, so pf4j resolves it against the repository URL as before.
            val relativePath = "${kind.lowercase()}/${jar.name}"
            jar.copyTo(outDir.resolve(relativePath), overwrite = true)
            val url = baseUrl?.let { "${it.trimEnd('/')}/$relativePath" } ?: relativePath
            // "sha512sum" (not "sha512") is the field pf4j's Sha512SumVerifier reads — an inline hex sum
            // makes it verify the downloaded JAR's digest (no extra fetch). A wrong key here fails installs.
            val release = linkedMapOf<String, Any>("version" to pluginVersion, "url" to url, "sha512sum" to sha512)
            release["date"] = Instant.ofEpochMilli(jar.lastModified()).toString()
            if (requires != null) release["requires"] = requires
            val entry =
                linkedMapOf<String, Any>(
                    "id" to id,
                    "name" to name,
                    "description" to "",
                    "provider" to provider,
                    "kind" to kind,
                )
            sourcesByJar[jar.name]?.let { entry["sources"] = it }
            entry["releases"] = listOf(release)
            entry
        }
    outDir.resolve("plugins.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(entries)))
    logger.lifecycle("Wrote ${entries.size} plugin(s) to ${outDir.resolve("plugins.json")}")
}

// Runs tools/ExtractSources.java on a forked Java 25 launcher (the Gradle daemon's own JVM is older
// and can't load the plugins' bytecode) and returns the per-jar Mihon-style source lists. Fails the
// build on extraction errors — a published plugins.json silently missing its sources would be worse.
fun extractSources(
    jars: List<File>,
    hostLibs: Collection<File>,
    javaExe: File,
    extractorSrc: File,
    outFile: File,
    logger: Logger,
): Map<String, Any?> {
    outFile.parentFile.mkdirs()
    val classpath = (jars + hostLibs).joinToString(File.pathSeparator)
    val process =
        ProcessBuilder(
            javaExe.absolutePath,
            "-cp",
            classpath,
            extractorSrc.absolutePath,
            outFile.absolutePath,
        ).redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "Source extraction failed:\n$output" }
    logger.lifecycle(output.trim())
    @Suppress("UNCHECKED_CAST")
    return JsonSlurper().parse(outFile) as Map<String, Any?>
}

// Public repo of the in-tree plugins. Serve build/plugins-repo over HTTP and point a
// repository's URL at it.
tasks.register("generatePluginsJson") {
    group = "kodex"
    description = "Builds ALL plugin JARs and writes a plugins.json repository under build/plugins-repo."
    // `elements` wires the dependency on the jar tasks.
    val pluginJars = devPlugins.elements
    val defaultVersion = version.toString()
    val outDir = layout.buildDirectory.dir("plugins-repo")
    val extractorSrc = layout.projectDirectory.file("tools/ExtractSources.java")
    val sourcesJson = layout.buildDirectory.file("tmp/plugin-sources.json")
    val java25 =
        project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }
    inputs.files(devPlugins)
    inputs.file(extractorSrc)
    outputs.dir(outDir)
    // Shared helper is a script member; keep this rarely-run publish task off the configuration cache.
    notCompatibleWithConfigurationCache("Manual publish task; uses a script-level helper.")
    doLast {
        val jars = pluginJars.get().map { it.asFile }
        val sourcesByJar =
            extractSources(
                jars,
                pluginHostLibs.files,
                java25.get().executablePath.asFile,
                extractorSrc.asFile,
                sourcesJson.get().asFile,
                logger,
            )
        writePluginsRepo(jars, outDir.get().asFile, defaultVersion, null, sourcesByJar, logger)
    }
}

// Incremental variant used by CI: rebuilds ONLY the given plugins and merges their fresh JARs over
// the previously published repository tree, so unchanged plugins keep byte-identical JARs (no hash
// churn on the repository branch). Plugins whose module no longer exists are pruned, then
// plugins.json is regenerated over the merged set — so an empty -PchangedPlugins run still applies
// deletions.
tasks.register("updatePluginsRepo") {
    group = "kodex"
    description =
        "Merges freshly built JARs of the given plugins (-PchangedPlugins=a,b) over a previous repository tree (-PpreviousRepo=...) and regenerates plugins.json under build/plugins-repo."
    val changed =
        (findProperty("changedPlugins") as String?)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() } ?: emptyList()
    val moduleNames = subprojects.map { it.name }.toSet()
    changed.forEach {
        require(it in moduleNames) { "Unknown plugin module '$it' (expected a src/{content,metadata} subproject)" }
        dependsOn(":$it:jar")
    }
    val previousRepoProp = providers.gradleProperty("previousRepo")
    val defaultVersion = version.toString()
    val outDir = layout.buildDirectory.dir("plugins-repo")
    val extractorSrc = layout.projectDirectory.file("tools/ExtractSources.java")
    val sourcesJson = layout.buildDirectory.file("tmp/plugin-sources.json")
    val java25 =
        project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }
    // Relative -P paths must resolve against the project dir, not the Gradle daemon's working directory.
    val rootDirFile = layout.projectDirectory.asFile
    notCompatibleWithConfigurationCache("CI publish task; uses a script-level helper.")
    doLast {
        val prevPath =
            previousRepoProp.orNull
                ?: error("Set -PpreviousRepo=<checkout of the published repository branch>")
        val prev = File(prevPath).let { if (it.isAbsolute) it else rootDirFile.resolve(prevPath) }
        require(prev.isDirectory) { "previousRepo is not a directory: $prev" }

        // <module>-<version>.jar → <module> (plugin versions are plain x.y.z — no dashes)
        fun moduleOf(jarName: String) = jarName.removeSuffix(".jar").substringBeforeLast('-')
        val kept =
            listOf("content", "metadata", "other")
                .asSequence()
                .mapNotNull { prev.resolve(it).listFiles() }
                .flatMap { it.asList() }
                .filter { it.extension == "jar" }
                .filter { moduleOf(it.name) in moduleNames } // prune plugins whose module was deleted
                .filter { moduleOf(it.name) !in changed }
                .toList() // replaced by the fresh build below
        val fresh =
            changed.map { name ->
                val libs =
                    project(":$name")
                        .layout.buildDirectory
                        .dir("libs")
                        .get()
                        .asFile
                libs.listFiles()?.filter { it.extension == "jar" }?.maxByOrNull { it.lastModified() }
                    ?: error("No jar built for :$name under $libs")
            }
        val jars = (kept + fresh).sortedBy { it.name }
        logger.lifecycle(
            "Merging ${fresh.size} rebuilt + ${kept.size} unchanged plugin jar(s) " +
                "(${moduleNames.size} modules in the build).",
        )
        val sourcesByJar =
            extractSources(
                jars,
                pluginHostLibs.files,
                java25.get().executablePath.asFile,
                extractorSrc.asFile,
                sourcesJson.get().asFile,
                logger,
            )
        writePluginsRepo(jars, outDir.get().asFile, defaultVersion, null, sourcesByJar, logger)
    }
}

// Private repo built from a directory of pre-built plugin JARs that live OUTSIDE this source tree
// (e.g. copyrighted plugins kept in a private git repo). Usage:
//   ./gradlew generatePrivatePluginsJson -PjarsDir=/path/to/jars [-PoutDir=/path/to/output] [-PbaseUrl=https://host/path]
// Then host the output dir behind auth and add it in Kodex with an access token.
tasks.register("generatePrivatePluginsJson") {
    group = "kodex"
    description =
        "Writes a plugins.json repository from a directory of pre-built plugin JARs (-PjarsDir=..., optional -PoutDir=..., -PbaseUrl=...)."
    val defaultVersion = version.toString()
    val jarsDirProp = providers.gradleProperty("jarsDir")
    val outDirProp = providers.gradleProperty("outDir")
    val baseUrlProp = providers.gradleProperty("baseUrl")
    val defaultOut = layout.buildDirectory.dir("private-plugins-repo")
    // Relative -P paths must resolve against the project dir, not the Gradle daemon's working directory.
    val rootDir = layout.projectDirectory.asFile
    notCompatibleWithConfigurationCache("Manual publish task; uses a script-level helper.")
    doLast {
        fun resolve(path: String) = File(path).let { if (it.isAbsolute) it else rootDir.resolve(path) }
        val jarsDir = resolve(jarsDirProp.orNull ?: error("Set -PjarsDir=<dir containing plugin .jar files>"))
        require(jarsDir.isDirectory) { "jarsDir is not a directory: $jarsDir" }
        val jars = jarsDir.listFiles()?.toList().orEmpty()
        val outDir = outDirProp.orNull?.let { resolve(it) } ?: defaultOut.get().asFile
        // Pre-built external jars may target a different SPI; no source extraction here.
        writePluginsRepo(jars, outDir, defaultVersion, baseUrlProp.orNull, emptyMap(), logger)
    }
}
