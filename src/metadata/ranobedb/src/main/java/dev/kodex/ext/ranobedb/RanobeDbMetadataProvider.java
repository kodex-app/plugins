package dev.kodex.ext.ranobedb;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Extension
public class RanobeDbMetadataProvider implements MetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(RanobeDbMetadataProvider.class);

    private static final String BASE_URL = "https://ranobedb.org/api/v0";
    private static final String SITE_URL = "https://ranobedb.org";
    private static final String IMAGE_URL = "https://images.ranobedb.org";
    private static final String USER_AGENT = "Kodex/1.0 (+https://github.com/kodex)";

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
        return "ranobedb";
    }

    @Override
    public String displayName() {
        return "RanobeDB";
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
        // ID-first: when a RanobeDB book id is already known, fetch it directly (no title search).
        String knownId = context.identifiers().get(MetadataIdentifiers.RANOBEDB);
        Long bookId;
        if (knownId != null && knownId.matches("\\d+")) {
            bookId = Long.parseLong(knownId);
            log.info("RanobeDB: direct book lookup id={} for series '{}'", bookId, context.name());
        } else {
            String name = context.name();
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            bookId = search(name);
            if (bookId == null) {
                log.info("RanobeDB: no book found for '{}'", name);
                return Optional.empty();
            }
        }
        JsonNode root = getJson(HttpUrl.get(BASE_URL + "/book/" + bookId));
        if (root == null) {
            return Optional.empty();
        }
        JsonNode book = root.path("book");
        if (!book.isObject()) {
            return Optional.empty();
        }
        log.info("RanobeDB: matched book {} for series '{}'", bookId, context.name());
        return Optional.of(toPatch(book));
    }

    /** First book id returned by the search endpoint, or null. */
    private Long search(String name) {
        HttpUrl url = HttpUrl.get(BASE_URL + "/books").newBuilder()
            .addQueryParameter("q", name)
            .addQueryParameter("limit", "5")
            .build();
        JsonNode root = getJson(url);
        if (root == null) {
            return null;
        }
        for (JsonNode hit : root.path("books")) {
            long id = hit.path("id").asLong(0);
            if (id != 0) {
                return id;
            }
        }
        return null;
    }

    private static SeriesMetadataPatch toPatch(JsonNode book) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();
        JsonNode series = book.path("series");

        long bookId = book.path("id").asLong(0);
        if (bookId != 0) {
            b.identifiers(Map.of(MetadataIdentifiers.RANOBEDB, String.valueOf(bookId)));
        }

        // Prefer the series title for series-level metadata; fall back to the (volume) book title.
        String title = series.isObject() ? text(series.get("title")) : null;
        if (title == null) {
            title = resolveTitle(book);
        }
        if (title != null) {
            b.title(title);
        }

        String description = text(book.get("description"));
        if (description == null || description.isBlank()) {
            description = text(book.get("description_ja"));
        }
        if (description != null && !description.isBlank()) {
            b.summary(description.strip());
        }

        publisher(book).ifPresent(b::publisher);

        String language = language(book);
        if (language != null) {
            b.language(language);
        }

        List<String> genres = genres(series);
        if (!genres.isEmpty()) {
            b.genres(genres);
        }

        JsonNode image = book.path("image");
        String filename = text(image.get("filename"));
        if (filename != null && !filename.isBlank()) {
            b.coverUrl(IMAGE_URL + "/" + filename);
        }

        if (series.isObject()) {
            long seriesId = series.path("id").asLong(0);
            if (seriesId != 0) {
                b.links(List.of(new WebLink("RanobeDB", SITE_URL + "/series/" + seriesId)));
            }
        } else if (bookId != 0) {
            b.links(List.of(new WebLink("RanobeDB", SITE_URL + "/book/" + bookId)));
        }
        return b.build();
    }

    /** Official English title, else romaji, else the default title. */
    private static String resolveTitle(JsonNode book) {
        for (JsonNode t : book.path("titles")) {
            if ("en".equals(text(t.get("lang"))) && t.path("official").asBoolean(false)) {
                String title = text(t.get("title"));
                if (title != null) {
                    return title;
                }
            }
        }
        String romaji = text(book.get("romaji"));
        return romaji != null ? romaji : text(book.get("title"));
    }

    /** First English "publisher"-type publisher (romaji preferred), if any. */
    private static Optional<String> publisher(JsonNode book) {
        for (JsonNode p : book.path("publishers")) {
            if ("en".equals(text(p.get("lang"))) && "publisher".equals(text(p.get("publisher_type")))) {
                String name = text(p.get("romaji"));
                return Optional.ofNullable(name != null ? name : text(p.get("name")));
            }
        }
        return Optional.empty();
    }

    /** "en" if any English release exists, else the original/listed language. */
    private static String language(JsonNode book) {
        for (JsonNode r : book.path("releases")) {
            if ("en".equals(text(r.get("lang")))) {
                return "en";
            }
        }
        String olang = text(book.get("olang"));
        return olang != null ? olang : text(book.get("lang"));
    }

    /** Distinct genre-type tags from the series record. */
    private static List<String> genres(JsonNode series) {
        if (!series.isObject()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode tag : series.path("tags")) {
            if ("genre".equals(text(tag.get("ttype")))) {
                String name = text(tag.get("name"));
                if (name != null && !name.isBlank()) {
                    out.add(name.strip());
                }
            }
        }
        return new ArrayList<>(out);
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
            if (!res.isSuccessful() || body == null) {
                return null;
            }
            return MAPPER.readTree(body.string());
        } catch (Exception e) {
            return null; // fail soft, per the SPI contract
        }
    }
}
