plugins {
    java
}

version = "1.0.2" // report fetch failures instead of an empty feed

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup) // the site is scraped HTML (host-provided)
    compileOnly(libs.okhttp) // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // the chapter list comes from a JSON ajax endpoint (host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "novelfire",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.novelfire.NovelFirePlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
