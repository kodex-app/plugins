plugins {
    java
}

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.jsoup)
    compileOnly(libs.okhttp)
    compileOnly(libs.jackson.databind)
    annotationProcessor(libs.pf4j)
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "kagane",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.ext.kagane.KaganePlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
