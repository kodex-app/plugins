plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "plugins"

// The SPI resolves from JitPack (see gradle/libs.versions.toml). When working on the SPI itself,
// temporarily uncomment this to substitute a sibling checkout for the published artifact (explicit
// substitution: the checkout's group is dev.kodex, not the JitPack com.github coordinate):
includeBuild("../spi") {
    dependencySubstitution {
        substitute(module("com.github.kodex-app:spi")).using(project(":"))
    }
}

listOf("content", "metadata").forEach { kind ->
    rootDir.resolve("src/$kind").listFiles()?.sortedBy { it.name }?.forEach { dir ->
        if (dir.isDirectory) {
            val name = "$kind-${dir.name}"
            include(name)
            project(":$name").projectDir = dir
        }
    }
}
