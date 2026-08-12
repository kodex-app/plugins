plugins {
    java
}

version = "1.0.1" // scanlator badge + season chapter numbering

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup) // provided by the host classloader at runtime
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "weebcentral",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.weebcentral.WeebCentralPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
