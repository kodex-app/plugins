plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup) // the whole family is scraped HTML (host-provided)
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // some chapter-list endpoints wrap their HTML in JSON (host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "readnovelfull",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.readnovelfull.ReadNovelFullPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
