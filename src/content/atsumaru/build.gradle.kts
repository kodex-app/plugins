plugins {
    java
}

version = "1.3.0" // report fetch failures instead of an empty feed

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "atsumaru",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.atsumaru.AtsumaruPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
