/*
 * metadata-anilist (INSTALLABLE) — a series MetadataProvider backed by the AniList GraphQL API
 * (graphql.anilist.co). Searches MANGA by title and maps the result to a patch (title, summary, status,
 * genres, cover, link).
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
    annotationProcessor(libs.pf4j)     // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "anilist",
            "Plugin-Name" to "AniList",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.ext.anilist.AniListPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
