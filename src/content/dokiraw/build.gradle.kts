plugins {
    java
}

version = "1.0.5" // domain moved to dokiraw.diy (upstream b658a2c22)

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup)
    compileOnly(libs.okhttp)
    annotationProcessor(libs.pf4j)
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "dokiraw",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.dokiraw.DokirawPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
