plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup) // the whole site is scraped HTML (host-provided)
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // the chapter listing is a JSON blob in a script tag (host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "ranobes",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.ranobes.RanobesPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
