plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup) // provided by the host classloader at runtime
    compileOnly(libs.okhttp) // outbound HTTP + multipart form posts (host-provided)
    compileOnly(libs.jackson.databind) // WordPress REST responses are JSON (host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "rawkuma",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.ext.rawkuma.RawkumaPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
