package dev.kodex.plugin.googlebooks;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.kodex.spi.PluginConfigSchema;
import dev.kodex.spi.PluginConfigSchema.Field;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.common.http.ProviderRateLimitException;
import dev.kodex.spi.metadata.MetadataCapability;
import dev.kodex.spi.metadata.MetadataIdentifiers;
import dev.kodex.spi.metadata.MetadataProvider;
import dev.kodex.spi.metadata.MetadataTarget;
import dev.kodex.spi.metadata.SeriesContext;
import dev.kodex.spi.metadata.SeriesMetadataPatch;
import dev.kodex.spi.metadata.TitleMatch;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Google Books series-metadata provider. Searches {@code www.googleapis.com/books/v1/volumes} by title
 * and maps the best-matching volume's {@code volumeInfo} to a {@link SeriesMetadataPatch}. An API key is
 * optional (raises the anonymous rate limit). HTTP goes through the core-provided client; JSON via Jackson.
 */
@Extension
public class GoogleBooksMetadataProvider implements MetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleBooksMetadataProvider.class);
    private static final String VOLUMES_URL = "https://www.googleapis.com/books/v1/volumes";
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
        return "googlebooks";
    }

    @Override
    public String displayName() {
        return "Google Books";
    }

    @Override
    public PluginConfigSchema configSchema() {
        return new PluginConfigSchema(List.of(
            Field.secret("apiKey", "API key (optional)", false)));
    }

    @Override
    public Set<MetadataCapability> capabilities() {
        return Set.of(MetadataCapability.TITLE, MetadataCapability.SUMMARY, MetadataCapability.PUBLISHER,
            MetadataCapability.GENRES, MetadataCapability.LANGUAGE, MetadataCapability.LINKS,
            MetadataCapability.IDENTIFIERS, MetadataCapability.THUMBNAIL);
    }

    @Override
    public boolean supports(MetadataTarget target) {
        return target == MetadataTarget.SERIES || target == MetadataTarget.ONESHOT;
    }

    @Override
    public Optional<SeriesMetadataPatch> seriesMetadata(SeriesContext context, ProviderSettings settings) {
        // ID-first: when a Google Books volume id is already known, fetch it directly (no title search).
        String knownId = context.identifiers().get(MetadataIdentifiers.GOOGLE_BOOKS);
        if (knownId != null && !knownId.isBlank()) {
            JsonNode volume = getVolumeById(knownId, settings);
            if (volume != null) {
                log.info("Google Books: direct volume lookup id={} for series '{}'", knownId, context.name());
                return Optional.of(toPatch(volume, context.name()));
            }
            log.info("Google Books: volume id={} not found, falling back to title search", knownId);
        }
        String name = context.name();
        if (name == null || name.isBlank()) {
            log.debug("Google Books: blank series name (id={}), skipping", context.seriesId());
            return Optional.empty();
        }
        String query = "intitle:" + name;
        HttpUrl.Builder url = HttpUrl.get(VOLUMES_URL).newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("maxResults", "5")
            .addQueryParameter("printType", "books");
        settings.getString("apiKey").ifPresentOrElse(
            k -> url.addQueryParameter("key", k),
            () -> log.debug("Google Books: no API key configured (using anonymous quota)"));

        HttpUrl built = url.build();
        log.info("Google Books: searching q='{}' for series '{}'", query, name);
        log.debug("Google Books: GET {}", redact(built));

        JsonNode root = getJson(built);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode items = root.path("items");
        int total = root.path("totalItems").asInt(items.size());
        log.info("Google Books: '{}' → totalItems={}, items returned={}", name, total, items.size());
        if (!items.isArray() || items.isEmpty()) {
            log.info("Google Books: no items for '{}' (the '-' in a title is read as a NOT operator by the "
                + "Google Books query parser; try a title without ' - ')", name);
            return Optional.empty();
        }
        JsonNode item = bestMatch(items, name);
        if (item == null) {
            log.info("Google Books: none of {} item(s) passed the title-match threshold for '{}'",
                items.size(), name);
            return Optional.empty();
        }
        log.info("Google Books: matched '{}' (publisher='{}') for series '{}'",
            text(item.path("volumeInfo").get("title")), text(item.path("volumeInfo").get("publisher")), name);
        return Optional.of(toPatch(item, name));
    }

    /** Fetches a single volume by its Google Books id; null when missing or on error. */
    private JsonNode getVolumeById(String volumeId, ProviderSettings settings) {
        HttpUrl.Builder url = HttpUrl.get(VOLUMES_URL + "/" + volumeId).newBuilder();
        settings.getString("apiKey").ifPresent(k -> url.addQueryParameter("key", k));
        JsonNode volume = getJson(url.build());
        return volume != null && volume.path("volumeInfo").isObject() ? volume : null;
    }

    /**
     * The highest-scoring item by title relevance, or null when nothing clears the match threshold.
     * Google's result order is "best effort" — a search for "A Guide for Background Characters to
     * Survive in a Manga" can lead with "How to Draw Manga Characters" — so result position is never
     * trusted on its own.
     */
    private static JsonNode bestMatch(JsonNode items, String name) {
        JsonNode best = null;
        double bestScore = 0;
        for (JsonNode item : items) {
            JsonNode info = item.path("volumeInfo");
            if (!info.isObject()) {
                continue;
            }
            double score = TitleMatch.score(name, text(info.get("title")));
            if (score > bestScore) {
                best = item;
                bestScore = score;
            }
        }
        if (bestScore < TitleMatch.DEFAULT_THRESHOLD) {
            return null;
        }
        return best;
    }

    /**
     * Maps a Google Books volume node ({@code id} + {@code volumeInfo}) to a series patch. Google Books
     * has no series concept — every result is a single volume — so the volume title is only trusted as
     * the series title when it matches the series name (normalized). Without this guard, a fallback match
     * like "Lout of Count's Family (Novel) Vol. 1" would rename the whole series.
     */
    private static SeriesMetadataPatch toPatch(JsonNode item, String seriesName) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();
        JsonNode info = item.path("volumeInfo");

        String volumeId = text(item.get("id"));
        if (volumeId != null && !volumeId.isBlank()) {
            b.identifiers(Map.of(MetadataIdentifiers.GOOGLE_BOOKS, volumeId));
        }

        String title = text(info.get("title"));
        if (title != null && sameTitle(title, seriesName)) {
            b.title(title);
        } else if (title != null) {
            log.info("Google Books: volume title '{}' does not match series '{}' — keeping the existing series title",
                title, seriesName);
        }
        String description = text(info.get("description"));
        if (description != null && !description.isBlank()) {
            b.summary(Jsoup.parse(description).wholeText().strip());
        }
        String publisher = text(info.get("publisher"));
        if (publisher != null && !publisher.isBlank()) {
            b.publisher(publisher);
        }
        String language = text(info.get("language"));
        if (language != null && !language.isBlank()) {
            b.language(language);
        }
        List<String> genres = new ArrayList<>();
        for (JsonNode c : info.path("categories")) {
            String genre = text(c);
            if (genre != null && !genre.isBlank()) {
                genres.add(genre);
            }
        }
        if (!genres.isEmpty()) {
            b.genres(genres);
        }
        String cover = coverUrl(info.path("imageLinks"));
        if (cover != null) {
            b.coverUrl(cover);
        }
        String link = text(info.get("infoLink"));
        if (link != null && !link.isBlank()) {
            b.links(List.of(new WebLink("Google Books", link)));
        }
        return b.build();
    }

    /** Prefer the largest available thumbnail; force HTTPS and drop the page-curl overlay. */
    private static String coverUrl(JsonNode imageLinks) {
        String url = null;
        for (String key : List.of("thumbnail", "smallThumbnail")) {
            String candidate = text(imageLinks.get(key));
            if (candidate != null && !candidate.isBlank()) {
                url = candidate;
                break;
            }
        }
        if (url == null) {
            return null;
        }
        return url.replaceFirst("^http://", "https://").replace("&edge=curl", "");
    }

    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asText() : null;
    }

    /** Case/punctuation-insensitive title equality (e.g. "Witch Hat Atelier." matches "witch hat atelier"). */
    private static boolean sameTitle(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String left = TitleMatch.normalize(a);
        return !left.isEmpty() && left.equals(TitleMatch.normalize(b));
    }

    private JsonNode getJson(HttpUrl url) {
        Request req = new Request.Builder().url(url).header("Accept", "application/json").get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            String payload = body == null ? "" : body.string();
            if (res.code() == 429) {
                // Rate-limited: signal the core so it cools this provider down instead of retrying per item.
                throw new ProviderRateLimitException(
                    "Google Books rate limit (HTTP 429)", retryAfterSeconds(res));
            }
            if (!res.isSuccessful()) {
                // Google returns a JSON error envelope (e.g. 403 quota); surface a snippet.
                log.warn("Google Books: HTTP {} — {}", res.code(),
                    payload.length() > 300 ? payload.substring(0, 300) + "…" : payload);
                return null;
            }
            return MAPPER.readTree(payload);
        } catch (ProviderRateLimitException e) {
            throw e; // must reach the core's cooldown handling — don't swallow with the IO failures below
        } catch (Exception e) {
            log.warn("Google Books: request failed for {}", redact(url), e);
            return null; // fail soft, per the SPI contract
        }
    }

    /** Parses a numeric {@code Retry-After} header; null when absent or not a number (date form). */
    private static Long retryAfterSeconds(Response res) {
        String header = res.header("Retry-After");
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The URL with any API key removed, for safe logging. */
    private static HttpUrl redact(HttpUrl url) {
        return url.newBuilder().removeAllQueryParameters("key").build();
    }
}
