package dev.kodex.plugin.hardcover;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
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
public class HardcoverMetadataProvider implements MetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(HardcoverMetadataProvider.class);

    private static final String ENDPOINT = "https://api.hardcover.app/v1/graphql";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String SEARCH_QUERY =
        "query BookSearch($q: String!, $limit: Int!) {"
            + " search(query: $q, query_type: \"Book\", per_page: $limit, page: 1) { results } }";
    private static final String LOOKUP_QUERY =
        "query BookBySlug($slug: String!) {"
            + " books(where: { slug: { _eq: $slug } }) {"
            + " id slug title subtitle description"
            + " featured_book_series { series { name } position }"
            + " image { url }"
            + " editions(limit: 1) { title subtitle publisher { name } language { code2 } image { url } } } }";

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
        return "hardcover";
    }

    @Override
    public String displayName() {
        return "Hardcover";
    }

    @Override
    public PluginConfigSchema configSchema() {
        return new PluginConfigSchema(List.of(
            Field.secret("apiKey", "API token", true)));
    }

    @Override
    public Set<MetadataCapability> capabilities() {
        return Set.of(MetadataCapability.TITLE, MetadataCapability.SUMMARY, MetadataCapability.GENRES,
            MetadataCapability.PUBLISHER, MetadataCapability.LANGUAGE, MetadataCapability.LINKS,
            MetadataCapability.IDENTIFIERS, MetadataCapability.THUMBNAIL);
    }

    @Override
    public boolean supports(MetadataTarget target) {
        return target == MetadataTarget.SERIES || target == MetadataTarget.ONESHOT;
    }

    @Override
    public Optional<SeriesMetadataPatch> seriesMetadata(SeriesContext context, ProviderSettings settings) {
        String apiKey = settings.getString("apiKey").orElse(null);
        if (apiKey == null) {
            log.debug("Hardcover: no API token configured, skipping");
            return Optional.empty();
        }

        // ID-first: when a Hardcover slug is already known, skip the search and look it up directly.
        // The search document (genres source) is then unavailable; genres come from the lookup only.
        JsonNode document;
        String slug;
        String knownSlug = context.identifiers().get(MetadataIdentifiers.HARDCOVER);
        if (knownSlug != null && !knownSlug.isBlank()) {
            document = MAPPER.createObjectNode();
            slug = knownSlug.trim();
            log.info("Hardcover: direct slug lookup {} for series '{}'", slug, context.name());
        } else {
            String name = context.name();
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            document = searchFirst(name, apiKey);
            if (document == null) {
                log.info("Hardcover: no results for '{}'", name);
                return Optional.empty();
            }
            slug = text(document.get("slug"));
        }
        // The search document already carries title/description/genres/cover; the slug lookup enriches it
        // with publisher + language from the primary edition. Enrichment is best-effort.
        JsonNode book = slug != null ? lookupBySlug(slug, apiKey) : null;
        log.info("Hardcover: matched '{}' (slug={}) for series '{}'",
            text(document.get("title")), slug, context.name());
        return Optional.of(toPatch(document, book, slug));
    }

    /** First search hit's typesense document node, or null. */
    private JsonNode searchFirst(String name, String apiKey) {
        ObjectNode variables = MAPPER.createObjectNode();
        variables.put("q", name);
        variables.put("limit", 5);
        JsonNode root = post(SEARCH_QUERY, variables, apiKey);
        if (root == null) {
            return null;
        }
        for (JsonNode hit : root.path("data").path("search").path("results").path("hits")) {
            JsonNode doc = hit.path("document");
            if (doc.isObject()) {
                return doc;
            }
        }
        return null;
    }

    /** Full book record (with one edition) for a slug, or null. */
    private JsonNode lookupBySlug(String slug, String apiKey) {
        ObjectNode variables = MAPPER.createObjectNode();
        variables.put("slug", slug);
        JsonNode root = post(LOOKUP_QUERY, variables, apiKey);
        if (root == null) {
            return null;
        }
        JsonNode books = root.path("data").path("books");
        return books.isArray() && !books.isEmpty() ? books.get(0) : null;
    }

    private static SeriesMetadataPatch toPatch(JsonNode document, JsonNode book, String slug) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();

        // Prefer the series name for series-level metadata, falling back to the book title.
        String seriesName = book != null
            ? text(book.path("featured_book_series").path("series").get("name")) : null;
        if (seriesName == null) {
            seriesName = text(document.path("featured_series").path("series").get("name"));
        }
        String title = seriesName != null ? seriesName : text(document.get("title"));
        if (title != null) {
            b.title(title);
        }

        String description = book != null ? text(book.get("description")) : null;
        if (description == null) {
            description = text(document.get("description"));
        }
        if (description != null && !description.isBlank()) {
            b.summary(Jsoup.parse(description).wholeText().strip());
        }

        List<String> genres = new ArrayList<>(new LinkedHashSet<>(strings(document.path("genres"))));
        if (!genres.isEmpty()) {
            b.genres(genres);
        }

        JsonNode edition = book != null ? book.path("editions").path(0) : null;
        if (edition != null && edition.isObject()) {
            String publisher = text(edition.path("publisher").get("name"));
            if (publisher != null && !publisher.isBlank()) {
                b.publisher(publisher);
            }
            String language = text(edition.path("language").get("code2"));
            if (language != null && !language.isBlank()) {
                b.language(language);
            }
        }

        String cover = coverUrl(document, book);
        if (cover != null) {
            b.coverUrl(cover);
        }

        if (slug != null && !slug.isBlank()) {
            b.identifiers(Map.of(MetadataIdentifiers.HARDCOVER, slug));
            b.links(List.of(new WebLink("Hardcover", "https://hardcover.app/books/" + slug)));
        }
        return b.build();
    }

    /** Edition cover, then book cover, then the search document's cover. */
    private static String coverUrl(JsonNode document, JsonNode book) {
        if (book != null) {
            String edition = text(book.path("editions").path(0).path("image").get("url"));
            if (edition != null && !edition.isBlank()) {
                return edition;
            }
            String bookCover = text(book.path("image").get("url"));
            if (bookCover != null && !bookCover.isBlank()) {
                return bookCover;
            }
        }
        String docCover = text(document.path("image").get("url"));
        return docCover != null && !docCover.isBlank() ? docCover : null;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            String value = text(node);
            if (value != null && !value.isBlank()) {
                out.add(value.strip());
            }
        }
        return out;
    }

    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asText() : null;
    }

    private JsonNode post(String query, JsonNode variables, String apiKey) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", query);
        payload.set("variables", variables);
        String token = apiKey.regionMatches(true, 0, "Bearer ", 0, 7) ? apiKey : "Bearer " + apiKey;

        Request req = new Request.Builder()
            .url(ENDPOINT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", token)
            .post(RequestBody.create(payload.toString(), JSON))
            .build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                log.warn("Hardcover: HTTP {}", res.code());
                return null;
            }
            JsonNode root = MAPPER.readTree(body.string());
            if (root.has("errors")) {
                log.warn("Hardcover: GraphQL errors — {}", root.get("errors"));
                return null;
            }
            return root;
        } catch (Exception e) {
            log.warn("Hardcover: request failed", e);
            return null; // fail soft, per the SPI contract
        }
    }
}
