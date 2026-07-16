/*
 * metadata-comicvine (INSTALLABLE) — a series MetadataProvider backed by the ComicVine API
 * (comicvine.gamespot.com/api), a comic database. Searches volumes by name and maps the best-matching
 * volume to a patch (title, summary, publisher, total issue count, cover, link).
 *
 * Requires an admin-supplied API key (declared via configSchema()); ComicVine rejects unauthenticated
 * requests and rate-limits per key. Packaged as a PF4J plugin JAR. The host provides kodex-spi, PF4J,
 * OkHttp, jsoup and Jackson at runtime; HTTP goes through the core-provided OkHttpClient (proxy + DoH)
 * and JSON is parsed with Jackson (exposed by kodex-spi), so the jar stays dependency-free.
 */

plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup)            // host-provided — strips HTML from descriptions
    compileOnly(libs.okhttp)           // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    annotationProcessor(libs.pf4j)     // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "comicvine",
            "Plugin-Name" to "ComicVine",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.ext.comicvine.ComicVinePlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
