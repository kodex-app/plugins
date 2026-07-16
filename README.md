# plugins-source

First-party installable plugins for [Kodex](https://github.com/kodex-app/kodex) — PF4J plugin JARs
providing content sources (`src/content/*`) and metadata providers (`src/metadata/*`).

Every plugin compiles against the SPI only ([spi](https://github.com/kodex-app/kodex-spi),
`compileOnly` via JitPack) — never against kodex internals. The host provides the SPI, PF4J, OkHttp,
Jackson, jsoup, and SLF4J at runtime, so plugin JARs stay dependency-free (no bundling/shading).

## Layout

Plugins are auto-discovered by `settings.gradle.kts`: every directory under `src/content` and
`src/metadata` becomes a subproject named `<kind>-<dirname>` — e.g. `src/content/weebcentral` is
`:content-weebcentral`. The name prefix carries the plugin kind through JAR names, the
`plugins.json` `kind` field, and the server's per-kind load directories.

## Plugin repository (the `repository` branch)

Every push to `dev` runs the [publish workflow](.github/workflows/publish-plugins.yml), which
builds all plugin JARs plus the pf4j-update `plugins.json` and force-pushes them to the `build`
branch. Add it to Kodex as a plugin repository:

```
https://raw.githubusercontent.com/kodex-app/kodex-plugins/repository/plugins.json
```

The branch keeps `plugins.json` at the root with the JARs filed into per-kind folders (`content/`,
`metadata/`); each release's relative `url` points into those folders, so pf4j resolves them
against the repository URL.

Publishing is **incremental**: only plugins whose sources changed in the push are rebuilt
(`updatePluginsRepo` merges them over the current branch tree, so unchanged JARs stay
byte-identical). Changes outside `src/`, a missing `repository` branch, a force push, or a manual
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

## Adding a new plugin

1. Create `src/content/<name>/` or `src/metadata/<name>/` — it is picked up automatically.
2. `build.gradle.kts`: `compileOnly(libs.kodex.spi)` + host-provided libs, `annotationProcessor(libs.pf4j)`,
   and a `jar` manifest with `Plugin-Id`, `Plugin-Version`, `Plugin-Class`, `Plugin-Provider`
   (copy an existing plugin as a template).

## License

[GPL-3.0](LICENSE).
