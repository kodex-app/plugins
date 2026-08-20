plugins {
    java
}

version = "1.0.1" // latest feed: comic-granular /api/search, deduped chapter-feed fallback

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup) // details/page-list payloads are embedded in the HTML (host-provided)
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "comick",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.comick.ComickPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
