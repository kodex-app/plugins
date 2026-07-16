/*
 * metadata-googlebooks (INSTALLABLE) — a series MetadataProvider backed by the Google Books API
 * (www.googleapis.com/books/v1). Searches by title and maps the best-matching volume to a patch
 * (title, summary, publisher, genres, language, cover, link).
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
    compileOnly(libs.jsoup)            // host-provided — strips HTML from descriptions
    compileOnly(libs.okhttp)           // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    compileOnly(libs.slf4j.api)        // logging — host-provided binding, loaded parent-first
    annotationProcessor(libs.pf4j)     // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "googlebooks",
            "Plugin-Name" to "Google Books",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.googlebooks.GoogleBooksPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
