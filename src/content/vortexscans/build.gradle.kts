plugins {
    java
}

version = "1.0.1" // report fetch failures instead of an empty feed

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON API parsing (host-provided)
    compileOnly(libs.jsoup) // strip HTML out of the API's description field (host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "vortexscans",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.vortexscans.VortexScansPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
