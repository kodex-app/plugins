/*
 * metadata-ranobedb (INSTALLABLE) — a series MetadataProvider backed by the RanobeDB API
 * (ranobedb.org/api/v0), a light-novel database. Searches books by title, fetches the best match, and maps
 * its series record to a patch (title, summary, publisher, genres, language, cover, link). No API key.
 *
 * Packaged as a PF4J plugin JAR. The host provides kodex-spi, PF4J, OkHttp and Jackson at runtime; HTTP
 * goes through the core-provided OkHttpClient (proxy + DoH) and JSON is parsed with Jackson (exposed by
 * kodex-spi), so the jar stays dependency-free — no bundling/shading.
 */

plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.okhttp)           // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // JSON parsing via Jackson (exposed by kodex-spi, host-provided)
    annotationProcessor(libs.pf4j)     // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "ranobedb",
            "Plugin-Name" to "RanobeDB",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.ext.ranobedb.RanobeDbPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
