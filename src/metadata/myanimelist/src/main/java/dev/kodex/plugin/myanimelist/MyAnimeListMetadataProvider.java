package dev.kodex.plugin.myanimelist;

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
 * MyAnimeList series-metadata provider via the free Jikan REST proxy ({@code api.jikan.moe/v4}). Searches
 * manga by title and maps the top hit to a {@link SeriesMetadataPatch} (title, summary, status, genres,
 * tags, publisher, cover, link). HTTP goes through the core-provided client; JSON via Jackson.
 */
@Extension
public class MyAnimeListMetadataProvider implements MetadataProvider {

    private static final String SEARCH_URL = "https://api.jikan.moe/v4/manga";
    private static final int MAX_TAGS = 15;
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
        return "myanimelist";
    }

    @Override
    public String displayName() {
        return "MyAnimeList";
    }

    @Override
    public Set<MetadataCapability> capabilities() {
        return Set.of(MetadataCapability.TITLE, MetadataCapability.SUMMARY, MetadataCapability.GENRES,
            MetadataCapability.TAGS, MetadataCapability.PUBLISHER, MetadataCapability.LINKS,
            MetadataCapability.THUMBNAIL);
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
            .addQueryParameter("limit", "1")
            .build();
        JsonNode root = getJson(url);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode manga = root.path("data").path(0);
        return manga.isObject() ? Optional.of(toPatch(manga)) : Optional.empty();
    }

    private static SeriesMetadataPatch toPatch(JsonNode manga) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();

        String title = firstNonBlank(text(manga.get("title_english")), text(manga.get("title")));
        if (title != null) {
            b.title(title);
        }
        String synopsis = text(manga.get("synopsis"));
        if (synopsis != null && !synopsis.isBlank()) {
            b.summary(Jsoup.parse(synopsis).wholeText().strip());
        }
        String status = mapStatus(text(manga.get("status")));
        if (status != null) {
            b.status(status);
        }
        List<String> genres = names(manga.path("genres"));
        if (!genres.isEmpty()) {
            b.genres(genres);
        }
        List<String> tags = names(manga.path("themes"));
        if (!tags.isEmpty()) {
            b.tags(tags.size() > MAX_TAGS ? tags.subList(0, MAX_TAGS) : tags);
        }
        List<String> serializations = names(manga.path("serializations"));
        if (!serializations.isEmpty()) {
            b.publisher(serializations.get(0));
        }
        String cover = firstNonBlank(
            text(manga.path("images").path("jpg").get("large_image_url")),
            text(manga.path("images").path("jpg").get("image_url")));
        if (cover != null) {
            b.coverUrl(cover);
        }
        String link = text(manga.get("url"));
        if (link != null && !link.isBlank()) {
            b.links(List.of(new WebLink("MyAnimeList", link)));
        }
        return b.build();
    }

    /** Collects the {@code name} field from a Jikan array of {id,type,name,url} objects. */
    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            String name = text(node.get("name"));
            if (name != null && !name.isBlank()) {
                out.add(name);
            }
        }
        return out;
    }

    /** Jikan manga statuses → Kodex series statuses. */
    private static String mapStatus(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toLowerCase();
        if (s.contains("finished")) return "ENDED";
        if (s.contains("publishing")) return "ONGOING";
        if (s.contains("hiatus")) return "HIATUS";
        if (s.contains("discontinued")) return "ABANDONED";
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
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
