plugins {
    java
}

version = "1.0.2" // popular = most followed among titles created in the last 30 days (upstream #18899)

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "mangadex",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.mangadex.MangaDexPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
