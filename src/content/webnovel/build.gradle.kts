plugins {
    java
}

version = "1.0.1" // adds the novel catalogue as a second source

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup) // the novel catalogue is scraped HTML (host-provided)
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "webnovel",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.webnovel.WebNovelPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
