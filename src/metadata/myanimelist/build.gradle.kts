/*
 * metadata-myanimelist (INSTALLABLE) — a series MetadataProvider backed by the Jikan API
 * (api.jikan.moe), the free unofficial REST proxy for MyAnimeList. Searches manga by title and maps the
 * top hit to a patch (title, summary, status, genres, tags, publisher, cover, link).
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
            "Plugin-Id" to "myanimelist",
            "Plugin-Name" to "MyAnimeList",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.myanimelist.MyAnimeListPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
