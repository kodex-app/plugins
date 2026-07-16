/*
 * metadata-goodreads (INSTALLABLE) — a series MetadataProvider that scrapes Goodreads.
 *
 * Goodreads retired its public API, so this uses the JSON autocomplete endpoint
 * (/book/auto_complete) to resolve a book id by title (the search pages sit behind WAF challenges),
 * then parses the book page's __NEXT_DATA__ Apollo state into a patch (title, summary, publisher,
 * language, genres, cover, link, goodreads id). No API key.
 *
 * Packaged as a PF4J plugin JAR. The host provides kodex-spi, PF4J, OkHttp, jsoup and Jackson at
 * runtime; HTTP goes through the core-provided OkHttpClient (proxy + DoH), so the jar stays
 * dependency-free — no bundling/shading.
 */

plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup)            // host-provided — extracts the __NEXT_DATA__ script from HTML
    compileOnly(libs.okhttp)           // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    annotationProcessor(libs.pf4j)     // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "goodreads",
            "Plugin-Name" to "Goodreads",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.ext.goodreads.GoodreadsPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
