/*
 * metadata-openlibrary (INSTALLABLE) — a series MetadataProvider backed by the Open Library API
 * (openlibrary.org). Searches by title, then fetches the matched work for its description, mapping the
 * result to a patch (title, summary, publisher, genres, language, cover, link). No API key required.
 *
 * Packaged as a PF4J plugin JAR. The host provides kodex-spi, PF4J, OkHttp and jsoup at runtime; HTTP
 * goes through the core-provided OkHttpClient (proxy + DoH) and JSON is parsed with Jackson (exposed by
 * kodex-spi), so the jar stays dependency-free — no bundling/shading.
 */

plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup)            // host-provided — strips any HTML from descriptions
    compileOnly(libs.okhttp)           // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    annotationProcessor(libs.pf4j)     // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "openlibrary",
            "Plugin-Name" to "Open Library",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.openlibrary.OpenLibraryPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
