plugins {
    java
}

// 1.0.1: ported off the retired v1 API onto /api/v2 (the old endpoints now answer 403).
version = "1.0.1"

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // the whole source is the nhentai JSON API (host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "nhentai",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.nhentai.NHentaiPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
