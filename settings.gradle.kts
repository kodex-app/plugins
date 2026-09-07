plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "plugins"
listOf("content", "metadata").forEach { kind ->
    rootDir.resolve("src/$kind").listFiles()?.sortedBy { it.name }?.forEach { dir ->
        if (dir.isDirectory) {
            val name = "$kind-${dir.name}"
            include(name)
            project(":$name").projectDir = dir
        }
    }
}
