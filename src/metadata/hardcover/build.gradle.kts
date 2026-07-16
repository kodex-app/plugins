/*
 * metadata-hardcover (INSTALLABLE) — a series MetadataProvider backed by the Hardcover GraphQL API
 * (api.hardcover.app/v1/graphql), a books database. Searches books by title, then looks up the best
 * match's editions, mapping to a patch (title, summary, genres, publisher, language, cover, link).
 *
 * Requires an admin-supplied API token (Bearer; declared via configSchema()). Packaged as a PF4J plugin
 * JAR. The host provides kodex-spi, PF4J, OkHttp, jsoup and Jackson at runtime; HTTP goes through the
 * core-provided OkHttpClient (proxy + DoH) and JSON is parsed with Jackson, so the jar stays
 * dependency-free — no bundling/shading.
 */

plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup)            // host-provided — strips HTML from descriptions
    compileOnly(libs.okhttp)           // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON (request + response) via Jackson (exposed by kodex-spi)
    annotationProcessor(libs.pf4j)     // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "hardcover",
            "Plugin-Name" to "Hardcover",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.hardcover.HardcoverPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
