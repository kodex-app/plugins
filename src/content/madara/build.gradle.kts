plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup)
    compileOnly(libs.okhttp)
    annotationProcessor(libs.pf4j)
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "madara",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.madara.MadaraPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
