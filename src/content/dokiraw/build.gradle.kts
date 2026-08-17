plugins {
    java
}

version = "1.0.2" // domain moved to dokiraw.beer

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
