package dev.kodex.ext.anilist;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.metadata.MetadataCapability;
import dev.kodex.spi.metadata.MetadataProvider;
import dev.kodex.spi.metadata.MetadataTarget;
import dev.kodex.spi.metadata.SeriesContext;
import dev.kodex.spi.metadata.SeriesMetadataPatch;
import dev.kodex.spi.common.model.WebLink;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.pf4j.Extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AniList series-metadata provider. Queries the AniList GraphQL API ({@code graphql.anilist.co}) for a
 * MANGA matching the series name and maps the {@code Media} node to a {@link SeriesMetadataPatch}
 * (title, summary, status, genres, cover, link). HTTP goes through the core-provided client.
 */
@Extension
public class AniListMetadataProvider implements MetadataProvider {

    private static final String API_URL = "https://graphql.anilist.co";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient FALLBACK = new OkHttpClient();

    private static final String QUERY = """
        query ($search: String) {
          Media(search: $search, type: MANGA) {
            title { romaji english }
            description(asHtml: false)
            status
            genres
            siteUrl
            coverImage { extraLarge large medium }
          }
        }""";

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
        return "anilist";
    }

    @Override
    public String displayName() {
        return "AniList";
    }

    @Override
    public Set<MetadataCapability> capabilities() {
        return Set.of(MetadataCapability.TITLE, MetadataCapability.SUMMARY, MetadataCapability.GENRES,
            MetadataCapability.LINKS, MetadataCapability.THUMBNAIL);
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
        ObjectNode variables = MAPPER.createObjectNode();
        variables.put("search", name);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", QUERY);
        payload.set("variables", variables);

        JsonNode root = post(payload.toString());
        if (root == null) {
            return Optional.empty();
        }
        JsonNode media = root.path("data").path("Media");
        return media.isObject() ? Optional.of(toPatch(media)) : Optional.empty();
    }

    private static SeriesMetadataPatch toPatch(JsonNode media) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();

        JsonNode title = media.path("title");
        String name = firstNonBlank(text(title.get("english")), text(title.get("romaji")));
        if (name != null) {
            b.title(name);
        }
        String description = text(media.get("description"));
        if (description != null && !description.isBlank()) {
            // AniList descriptions contain lightweight HTML (<br>, <i>, …) even with asHtml:false.
            b.summary(Jsoup.parse(description.replaceAll("(?i)<br\\s*/?>", "\n")).wholeText().strip());
        }
        String status = mapStatus(text(media.get("status")));
        if (status != null) {
            b.status(status);
        }
        List<String> genres = new ArrayList<>();
        for (JsonNode g : media.path("genres")) {
            String genre = text(g);
            if (genre != null && !genre.isBlank()) {
                genres.add(genre);
            }
        }
        if (!genres.isEmpty()) {
            b.genres(genres);
        }
        JsonNode cover = media.path("coverImage");
        String coverUrl = firstNonBlank(text(cover.get("extraLarge")), text(cover.get("large")),
            text(cover.get("medium")));
        if (coverUrl != null) {
            b.coverUrl(coverUrl);
        }
        String siteUrl = text(media.get("siteUrl"));
        if (siteUrl != null && !siteUrl.isBlank()) {
            b.links(List.of(new WebLink("AniList", siteUrl)));
        }
        return b.build();
    }

    /** AniList MANGA statuses → Kodex series statuses. */
    private static String mapStatus(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toUpperCase()) {
            case "RELEASING" -> "ONGOING";
            case "FINISHED" -> "ENDED";
            case "HIATUS" -> "HIATUS";
            case "CANCELLED" -> "ABANDONED";
            default -> null;
        };
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

    private JsonNode post(String body) {
        Request req = new Request.Builder()
            .url(API_URL)
            .header("Accept", "application/json")
            .post(RequestBody.create(body, JSON))
            .build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody responseBody = res.body();
            if (!res.isSuccessful() || responseBody == null) {
                return null;
            }
            return MAPPER.readTree(responseBody.string());
        } catch (Exception e) {
            return null; // fail soft, per the SPI contract
        }
    }
}
