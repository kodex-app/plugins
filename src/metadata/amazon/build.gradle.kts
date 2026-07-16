/*
 * metadata-amazon (INSTALLABLE) — a series MetadataProvider that scrapes the public Amazon storefront.
 *
 * Amazon has no usable public metadata API (PA-API 5.0 needs an approved Associates account + SigV4
 * signing), so this searches /s?i=stripbooks for ASINs and parses the first /dp/{asin} product page into
 * a patch (title, summary, publisher, language, genres, cover, link). The admin may set a regional domain
 * and an optional session cookie via configSchema(). Scraping is brittle and rate-limited by design.
 *
 * Packaged as a PF4J plugin JAR. The host provides kodex-spi, PF4J, OkHttp, jsoup and Jackson at runtime;
 * HTTP goes through the core-provided OkHttpClient (proxy + DoH), so the jar stays dependency-free.
 */

plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup)            // host-provided — HTML parsing/scraping
    compileOnly(libs.okhttp)           // outbound HTTP via the core-provided OkHttpClient (host-provided)
    compileOnly(libs.jackson.databind) // parses the cover data-a-dynamic-image JSON (host-provided)
    annotationProcessor(libs.pf4j) // generates META-INF/extensions.idx for @Extension classes
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "amazon",
            "Plugin-Name" to "Amazon",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.amazon.AmazonPlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
