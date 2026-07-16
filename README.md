# plugins-source

First-party installable plugins for [Kodex](https://github.com/kodex-app/kodex) — PF4J plugin JARs
providing content sources (`src/content/*`) and metadata providers (`src/metadata/*`).

Every plugin compiles against the SPI only ([spi](https://github.com/kodex-app/kodex-spi),
`compileOnly` via JitPack) — never against kodex internals. The host provides the SPI, PF4J, OkHttp,
Jackson, jsoup, and SLF4J at runtime, so plugin JARs stay dependency-free (no bundling/shading).

## Installing these plugins in Kodex

These plugins are published as a **plugin repository** — a single `plugins.json` index that Kodex
reads to list, install, and update plugins. Add it to your server once, then install plugins from
the UI:

1. Sign in to Kodex as an **admin** and open **Plugins** in the sidebar.
2. Go to the **Settings** tab → **Repositories** → **Add**, then fill in:
   - **Name** — anything, e.g. `Kodex first-party`.
   - **URL** — paste:
     ```
     https://raw.githubusercontent.com/kodex-app/plugins/refs/heads/repo/plugins.json
     ```
   - (Private repo? add an **access token** — sent as the `Authorization` header, stored encrypted.)
3. Open the **Browse** tab, hit **Refresh** if needed, and **Install** the content sources /
   metadata providers you want. Installed plugins load immediately; some may need a server restart.

That URL points at the `repo` branch of this repository (see below), which is regenerated on every
push to `dev`.

## Layout

Plugins are auto-discovered by `settings.gradle.kts`: every directory under `src/content` and
`src/metadata` becomes a subproject named `<kind>-<dirname>` — e.g. `src/content/weebcentral` is
`:content-weebcentral`. The name prefix carries the plugin kind through JAR names, the
`plugins.json` `kind` field, and the server's per-kind load directories.

## How the plugin repository is published (the `repo` branch)

Every push to `dev` runs the [publish workflow](.github/workflows/publish-plugins.yml), which
builds all plugin JARs plus the pf4j-update `plugins.json` and force-pushes them to the **`repo`**
branch. That branch **is** the public plugin repository users point Kodex at:

```
https://raw.githubusercontent.com/kodex-app/plugins/refs/heads/repo/plugins.json
```

The branch keeps `plugins.json` at the root with the JARs filed into per-kind folders (`content/`,
`metadata/`); each release's relative `url` points into those folders, so pf4j resolves them
against the repository URL.

Publishing is **incremental**: only plugins whose sources changed in the push are rebuilt
(`updatePluginsRepo` merges them over the current branch tree, so unchanged JARs stay
byte-identical). Changes outside `src/`, a missing `repo` branch, a force push, or a manual
workflow dispatch trigger a full rebuild of all plugins.

Beyond the standard pf4j-update fields, each entry carries `kind` (CONTENT/METADATA) and — for
content plugins — a Mihon-index-style `sources` array (`{id, name, lang, baseUrl}` per bundled
source). The `id`s are the sources' Mihon-aligned `ContentSource.id()` values, extracted from the
built JARs at generation time ([tools/ExtractSources.java](tools/ExtractSources.java)), so a Mihon
backup's numeric source id can be matched to the plugin that provides it.

## Building locally

```bash
./gradlew build                        # compile + package every plugin JAR
./gradlew :content-weebcentral:jar     # a single plugin
./gradlew generatePluginsJson          # the full plugin repo under build/plugins-repo
```

Requires a JDK 25 toolchain (auto-provisioned) and network access to JitPack for the SPI.

## Deploying to a dev server

```bash
./gradlew installDevPlugins            # copy JARs into ../kodex/data/plugins/{content,metadata}
./gradlew installDevPlugins -PkodexDataDir=/path/to/kodex/data   # non-sibling layout
```

Restart the Kodex server afterwards — plugins are loaded at startup.

## Creating a new plugin

A plugin is a small Gradle subproject: a PF4J entry-point class plus one or more `@Extension`
implementations of an SPI interface. The fastest start is to copy the closest existing plugin, but
here is the whole shape from scratch. This example makes a metadata provider called `mysource`;
for a content source, use `src/content/…` and implement `ContentSource` instead.

**1. Create the module directory.** The name after `content/` or `metadata/` becomes the plugin id
prefix and is auto-discovered — no `settings.gradle.kts` edit needed.

```
src/metadata/mysource/
├── build.gradle.kts
└── src/main/java/dev/kodex/plugin/mysource/
    ├── MySourcePlugin.java
    └── MySourceMetadataProvider.java
```

**2. `build.gradle.kts`** — SPI + host libs are `compileOnly` (never bundled); `pf4j` is an
annotation processor that generates `META-INF/extensions.idx` for your `@Extension` classes. The
`Plugin-Class` must be the fully-qualified entry-point class.

```kotlin
plugins { java }

dependencies {
    compileOnly(libs.kodex.spi)
    compileOnly(libs.okhttp)           // outbound HTTP via the core-provided OkHttpClient
    compileOnly(libs.jackson.databind) // JSON via Jackson (exposed by kodex-spi)
    compileOnly(libs.jsoup)            // optional — HTML parsing/cleanup
    annotationProcessor(libs.pf4j)     // generates META-INF/extensions.idx
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Id" to "mysource",
            "Plugin-Name" to "My Source",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Class" to "dev.kodex.plugin.mysource.MySourcePlugin",
            "Plugin-Provider" to "Kodex",
        )
    }
}
```

**3. The PF4J entry point** — extends `KodexPlugin`, nothing more:

```java
package dev.kodex.plugin.mysource;

import dev.kodex.spi.KodexPlugin;

/** PF4J entry point for the My Source metadata provider. */
public class MySourcePlugin extends KodexPlugin {
}
```

**4. The extension** — annotate with `@Extension` and implement the SPI interface
(`dev.kodex.spi.metadata.MetadataProvider` for metadata, `dev.kodex.spi.content.ContentSource` for
content). Do outbound HTTP through the host-provided `OkHttpClient` (proxy + DoH aware) rather than
`new OkHttpClient()`:

```java
package dev.kodex.plugin.mysource;

import dev.kodex.spi.metadata.MetadataProvider;
import org.pf4j.Extension;

@Extension
public class MySourceMetadataProvider implements MetadataProvider {
    // implement the interface — id/name, search, and mapping to a SeriesMetadataPatch
}
```

**5. Build and try it:**

```bash
./gradlew :metadata-mysource:jar        # compile + package just this plugin
./gradlew installDevPlugins             # deploy to ../kodex/data/plugins, then restart Kodex
```

Notes:

- **Stay dependency-free** — declare host libs as `compileOnly`; the runtime classpath is provided
  by the server, and shaded/bundled classes will clash.
- **One JAR can bundle multiple sources** — add more `@Extension` classes (see `kagane` with
  per-language subclasses, or `madara`/`hentaifox` with two each). A plugin id is not a source id.
- **Content sources** ported from Mihon/Keiyoushi should keep `displayName()` / `language()` /
  `versionId()` matching the upstream source so the recomputed `ContentSource.id()` lines up with
  Mihon backups.

## License

[GPL-3.0](LICENSE).
