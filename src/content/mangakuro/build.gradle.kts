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
            "Plugin-Id" to "mangakuro",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.mangakuro.MangaKuroPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
