plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup) // provided by the host classloader at runtime
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // gallery page list is an embedded JSON object (host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "hentaifox",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.ext.hentaifox.HentaiFoxPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
