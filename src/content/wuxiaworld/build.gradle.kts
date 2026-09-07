plugins {
    java
}

version = "1.0.1" // report fetch failures instead of an empty feed

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup) // strip HTML from synopsis/description (host-provided)
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "wuxiaworld",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.wuxiaworld.WuxiaWorldPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
