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
            "Plugin-Id" to "dokiraw",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.ext.dokiraw.DokirawPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
