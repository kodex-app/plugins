package dev.kodex.plugin.mangabaka;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.metadata.MetadataCapability;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MangaBaka series-metadata provider. Searches {@code api.mangabaka.dev/v1/series/search} by title and
 * maps the best-matching series record to a {@link SeriesMetadataPatch} (title, summary, status, genres,
 * publisher, cover, link). HTTP goes through the core-provided client; JSON via Jackson.
 */
@Extension
public class MangaBakaMetadataProvider implements MetadataProvider {

    private static final String SEARCH_URL = "https://api.mangabaka.dev/v1/series/search";
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
        return "mangabaka";
    }

    @Override
    public String displayName() {
        return "MangaBaka";
    }

    @Override
    public Set<MetadataCapability> capabilities() {
        return Set.of(MetadataCapability.TITLE, MetadataCapability.SUMMARY, MetadataCapability.GENRES,
            MetadataCapability.PUBLISHER, MetadataCapability.LINKS, MetadataCapability.THUMBNAIL);
    }

    @Override
    public boolean supports(MetadataTarget target) {
        return target == MetadataTarget.SERIES || target == MetadataTarget.ONESHOT;
    }

    @Override
    public Optional<SeriesMetadataPatch> seriesMetadata(SeriesContext context, ProviderSettings settings) {
        String name = context.name();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        HttpUrl url = HttpUrl.get(SEARCH_URL).newBuilder()
            .addQueryParameter("q", name)
            .addQueryParameter("limit", "5")
            .build();
        JsonNode root = getJson(url);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode record = bestMatch(root.path("data"), name);
        return record == null ? Optional.empty() : Optional.of(toPatch(record));
    }

    /** The record whose title matches exactly (case-insensitive), else the first record. */
    private static JsonNode bestMatch(JsonNode data, String name) {
        JsonNode first = null;
        for (JsonNode record : data) {
            if (!record.isObject()) {
                continue;
            }
            if (first == null) {
                first = record;
            }
            if (name.equalsIgnoreCase(text(record.get("title")))) {
                return record;
            }
        }
        return first;
    }

    private static SeriesMetadataPatch toPatch(JsonNode record) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();

        String title = text(record.get("title"));
        if (title != null) {
            b.title(title);
        }
        String description = text(record.get("description"));
        if (description != null && !description.isBlank()) {
            b.summary(Jsoup.parse(description).wholeText().strip());
        }
        String status = mapStatus(text(record.get("status")));
        if (status != null) {
            b.status(status);
        }
        List<String> genres = new ArrayList<>();
        for (JsonNode g : record.path("genres")) {
            // genres may be plain strings or {name:...} objects.
            String genre = g.isObject() ? text(g.get("name")) : text(g);
            if (genre != null && !genre.isBlank()) {
                genres.add(genre);
            }
        }
        if (!genres.isEmpty()) {
            b.genres(genres);
        }
        for (JsonNode p : record.path("publishers")) {
            String publisher = p.isObject() ? text(p.get("name")) : text(p);
            if (publisher != null && !publisher.isBlank()) {
                b.publisher(publisher);
                break;
            }
        }
        String cover = coverUrl(record.path("cover"));
        if (cover != null) {
            b.coverUrl(cover);
        }
        long id = record.path("id").asLong(0);
        if (id != 0) {
            b.links(List.of(new WebLink("MangaBaka", "https://mangabaka.dev/" + id)));
        }
        return b.build();
    }

    /** Cover is a map of size buckets ({@code x250}/{@code x500}/…), each with density variants (x1/x2). */
    private static String coverUrl(JsonNode cover) {
        if (!cover.isObject()) {
            return text(cover);
        }
        for (String size : List.of("x500", "x250", "default", "raw")) {
            JsonNode bucket = cover.path(size);
            String url = bucket.isObject() ? firstString(bucket) : text(bucket);
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return firstString(cover);
    }

    /** First scalar string value among a node's fields (handles unknown cover shapes defensively). */
    private static String firstString(JsonNode node) {
        for (JsonNode child : node) {
            String value = child.isObject() ? firstString(child) : text(child);
            if (value != null && value.startsWith("http")) {
                return value;
            }
        }
        return null;
    }

    /** MangaBaka statuses → Kodex series statuses. */
    private static String mapStatus(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toLowerCase();
        if (s.contains("complete") || s.contains("finished")) return "ENDED";
        if (s.contains("ongoing") || s.contains("releasing")) return "ONGOING";
        if (s.contains("hiatus") || s.contains("paused")) return "HIATUS";
        if (s.contains("cancel") || s.contains("discontin")) return "ABANDONED";
        return null;
    }

    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asText() : null;
    }

    private JsonNode getJson(HttpUrl url) {
        Request req = new Request.Builder().url(url).header("Accept", "application/json").get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                return null;
            }
            return MAPPER.readTree(body.string());
        } catch (Exception e) {
            return null; // fail soft, per the SPI contract
        }
    }
}
