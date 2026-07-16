package dev.kodex.plugin.comicvine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.kodex.spi.PluginConfigSchema;
import dev.kodex.spi.PluginConfigSchema.Field;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.metadata.MetadataCapability;
import dev.kodex.spi.metadata.MetadataIdentifiers;
import dev.kodex.spi.metadata.MetadataProvider;
import dev.kodex.spi.metadata.MetadataTarget;
import dev.kodex.spi.metadata.SeriesContext;
import dev.kodex.spi.metadata.SeriesMetadataPatch;
import dev.kodex.spi.common.model.WebLink;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Extension
public class ComicVineMetadataProvider implements MetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(ComicVineMetadataProvider.class);

    private static final String VOLUMES_URL = "https://comicvine.gamespot.com/api/volumes/";
    private static final String VOLUME_URL = "https://comicvine.gamespot.com/api/volume/4000-";
    private static final String VOLUME_FIELDS =
        "id,name,publisher,start_year,count_of_issues,description,deck,image,site_detail_url";
    private static final String USER_AGENT = "Kodex/1.0 (Comic Library Manager)";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.httpClient() : FALLBACK;
    }

    @Override
    public String id() {
        return "comicvine";
    }

    @Override
    public String displayName() {
        return "ComicVine";
    }

    @Override
    public PluginConfigSchema configSchema() {
        return new PluginConfigSchema(List.of(
            Field.secret("apiKey", "API key", true)));
    }

    @Override
    public Set<MetadataCapability> capabilities() {
        return Set.of(MetadataCapability.TITLE, MetadataCapability.SUMMARY, MetadataCapability.PUBLISHER,
            MetadataCapability.LINKS, MetadataCapability.IDENTIFIERS, MetadataCapability.THUMBNAIL);
    }

    @Override
    public boolean supports(MetadataTarget target) {
        return target == MetadataTarget.SERIES || target == MetadataTarget.ONESHOT;
    }

    @Override
    public Optional<SeriesMetadataPatch> seriesMetadata(SeriesContext context, ProviderSettings settings) {
        String apiKey = settings.getString("apiKey").orElse(null);
        if (apiKey == null) {
            log.debug("ComicVine: no API key configured, skipping");
            return Optional.empty();
        }
        // ID-first: when a ComicVine volume id is already known, fetch it directly (no name search).
        String knownId = context.identifiers().get(MetadataIdentifiers.COMICVINE);
        if (knownId != null && !knownId.isBlank()) {
            JsonNode volume = getVolumeById(knownId, apiKey);
            if (volume != null) {
                log.info("ComicVine: direct volume lookup id={} for series '{}'", knownId, context.name());
                return Optional.of(toPatch(volume));
            }
            log.info("ComicVine: volume id={} not found, falling back to name search", knownId);
        }
        String name = context.name();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        HttpUrl url = HttpUrl.get(VOLUMES_URL).newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("format", "json")
            .addQueryParameter("filter", "name:" + name)
            .addQueryParameter("field_list", VOLUME_FIELDS)
            .addQueryParameter("limit", "20")
            .build();

        JsonNode root = getJson(url);
        if (root == null || root.path("status_code").asInt(0) != 1) {
            if (root != null) {
                log.warn("ComicVine: API error for '{}' — {}", name, text(root.get("error")));
            }
            return Optional.empty();
        }
        JsonNode volume = bestMatch(root.path("results"), name);
        if (volume == null) {
            log.info("ComicVine: no volumes for '{}'", name);
            return Optional.empty();
        }
        log.info("ComicVine: matched volume {} ('{}') for series '{}'",
            volume.path("id").asLong(0), text(volume.get("name")), name);
        return Optional.of(toPatch(volume));
    }

    /** Fetches a single volume by its ComicVine id; null when missing, on error, or rate-limited. */
    private JsonNode getVolumeById(String volumeId, String apiKey) {
        HttpUrl url = HttpUrl.get(VOLUME_URL + volumeId + "/").newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("format", "json")
            .addQueryParameter("field_list", VOLUME_FIELDS)
            .build();
        JsonNode root = getJson(url);
        if (root == null || root.path("status_code").asInt(0) != 1) {
            return null;
        }
        JsonNode results = root.path("results");
        return results.isObject() ? results : null;
    }

    /** Exact name match (case-insensitive); otherwise the most recent volume by start_year. */
    private static JsonNode bestMatch(JsonNode results, String name) {
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        JsonNode newest = null;
        int newestYear = Integer.MIN_VALUE;
        for (JsonNode v : results) {
            if (!v.isObject()) {
                continue;
            }
            if (name.equalsIgnoreCase(text(v.get("name")))) {
                return v;
            }
            int year = parseYear(text(v.get("start_year")));
            if (newest == null || year > newestYear) {
                newest = v;
                newestYear = year;
            }
        }
        return newest;
    }

    private static int parseYear(String raw) {
        if (raw == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static SeriesMetadataPatch toPatch(JsonNode volume) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();

        long volumeId = volume.path("id").asLong(0);
        if (volumeId != 0) {
            b.identifiers(Map.of(MetadataIdentifiers.COMICVINE, String.valueOf(volumeId)));
        }
        String title = text(volume.get("name"));
        if (title != null) {
            b.title(title);
        }
        String description = text(volume.get("description"));
        if (description == null || description.isBlank()) {
            description = text(volume.get("deck"));
        }
        if (description != null && !description.isBlank()) {
            b.summary(Jsoup.parse(description).wholeText().strip());
        }
        String publisher = text(volume.path("publisher").get("name"));
        if (publisher != null && !publisher.isBlank()) {
            b.publisher(publisher);
        }
        int issueCount = volume.path("count_of_issues").asInt(0);
        if (issueCount > 0) {
            b.totalBookCount(issueCount);
        }
        String cover = text(volume.path("image").get("original_url"));
        if (cover != null && !cover.isBlank()) {
            b.coverUrl(cover);
        }
        String link = text(volume.get("site_detail_url"));
        if (link != null && !link.isBlank()) {
            b.links(List.of(new WebLink("ComicVine", link)));
        }
        return b.build();
    }

    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asText() : null;
    }

    private JsonNode getJson(HttpUrl url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            if (res.code() == 420) {
                log.warn("ComicVine: rate-limited (HTTP 420)");
                return null;
            }
            if (!res.isSuccessful() || body == null) {
                log.warn("ComicVine: HTTP {} for {}", res.code(), redact(url));
                return null;
            }
            return MAPPER.readTree(body.string());
        } catch (Exception e) {
            log.warn("ComicVine: request failed for {}", redact(url), e);
            return null; // fail soft, per the SPI contract
        }
    }

    /** The URL with the API key removed, for safe logging. */
    private static HttpUrl redact(HttpUrl url) {
        return url.newBuilder().removeAllQueryParameters("api_key").build();
    }
}
