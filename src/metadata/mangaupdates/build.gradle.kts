plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup) // host-provided — used to strip HTML from the description
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "mangaupdates",
            "Plugin-Name" to "MangaUpdates",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.ext.mangaupdates.MangaUpdatesPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
