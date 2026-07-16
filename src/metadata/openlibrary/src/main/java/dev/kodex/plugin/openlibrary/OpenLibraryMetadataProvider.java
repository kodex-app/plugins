package dev.kodex.plugin.openlibrary;

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
 * Open Library series-metadata provider. Searches {@code openlibrary.org/search.json} by title, then
 * fetches the matched work (its {@code .json}) for a description, mapping the result to a
 * {@link SeriesMetadataPatch} (title, summary, publisher, genres, language, cover, link). No API key.
 * HTTP goes through the core-provided client; JSON via Jackson.
 */
@Extension
public class OpenLibraryMetadataProvider implements MetadataProvider {

    private static final String SEARCH_URL = "https://openlibrary.org/search.json";
    private static final String BASE_URL = "https://openlibrary.org";
    private static final String COVER_URL = "https://covers.openlibrary.org/b/id/%s-L.jpg";
    private static final int MAX_GENRES = 15;
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
        return "openlibrary";
    }

    @Override
    public String displayName() {
        return "Open Library";
    }

    @Override
    public Set<MetadataCapability> capabilities() {
        return Set.of(MetadataCapability.TITLE, MetadataCapability.SUMMARY, MetadataCapability.PUBLISHER,
            MetadataCapability.GENRES, MetadataCapability.LANGUAGE, MetadataCapability.LINKS,
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
            .addQueryParameter("title", name)
            .addQueryParameter("limit", "5")
            // Trim the (large) default response to the fields we map.
            .addQueryParameter("fields", "key,title,author_name,publisher,subject,language,cover_i,first_publish_year")
            .build();
        JsonNode root = getJson(url);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode doc = bestMatch(root.path("docs"), name);
        return doc == null ? Optional.empty() : Optional.of(toPatch(doc));
    }

    /** The doc whose title matches exactly (case-insensitive), else the first doc. */
    private static JsonNode bestMatch(JsonNode docs, String name) {
        JsonNode first = null;
        for (JsonNode doc : docs) {
            if (!doc.isObject()) {
                continue;
            }
            if (first == null) {
                first = doc;
            }
            if (name.equalsIgnoreCase(text(doc.get("title")))) {
                return doc;
            }
        }
        return first;
    }

    private SeriesMetadataPatch toPatch(JsonNode doc) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();

        String title = text(doc.get("title"));
        if (title != null) {
            b.title(title);
        }
        for (JsonNode p : doc.path("publisher")) {
            String publisher = text(p);
            if (publisher != null && !publisher.isBlank()) {
                b.publisher(publisher);
                break;
            }
        }
        for (JsonNode l : doc.path("language")) {
            String language = text(l);
            if (language != null && !language.isBlank()) {
                b.language(language);
                break;
            }
        }
        List<String> genres = new ArrayList<>();
        for (JsonNode s : doc.path("subject")) {
            String subject = text(s);
            if (subject != null && !subject.isBlank() && genres.size() < MAX_GENRES) {
                genres.add(subject);
            }
        }
        if (!genres.isEmpty()) {
            b.genres(genres);
        }
        JsonNode coverId = doc.get("cover_i");
        if (coverId != null && coverId.isNumber()) {
            b.coverUrl(String.format(COVER_URL, coverId.asLong()));
        }
        String key = text(doc.get("key")); // e.g. "/works/OL12345W"
        if (key != null && !key.isBlank()) {
            b.links(List.of(new WebLink("Open Library", BASE_URL + key)));
            description(key).ifPresent(b::summary);
        }
        return b.build();
    }

    /** The work's description (search results don't include it), as plain text. */
    private Optional<String> description(String workKey) {
        JsonNode work = getJson(HttpUrl.get(BASE_URL + workKey + ".json"));
        if (work == null) {
            return Optional.empty();
        }
        // Open Library descriptions are either a plain string or a { "type", "value" } object.
        JsonNode description = work.get("description");
        String raw = description == null ? null
            : description.isObject() ? text(description.get("value")) : text(description);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Jsoup.parse(raw).wholeText().strip());
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
