package dev.kodex.plugin.goodreads;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import org.jsoup.nodes.Element;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class GoodreadsMetadataProvider implements MetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(GoodreadsMetadataProvider.class);

    private static final String AUTOCOMPLETE_URL = "https://www.goodreads.com/book/auto_complete";
    private static final String BOOK_URL = "https://www.goodreads.com/book/show/";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/131.0.0.0 Safari/537.36";
    private static final Pattern BOOK_ID_IN_URL = Pattern.compile("/book/show/(\\d+)");

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
        return "goodreads";
    }

    @Override
    public String displayName() {
        return "Goodreads";
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
        // ID-first: when a Goodreads book id is already known, fetch its page directly (no search).
        String knownId = context.identifiers().get(MetadataIdentifiers.GOODREADS);
        String bookId;
        if (knownId != null && knownId.matches("\\d+")) {
            bookId = knownId;
            log.info("Goodreads: direct book lookup id={} for series '{}'", bookId, context.name());
        } else {
            String name = context.name();
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            bookId = searchBookId(name);
            if (bookId == null) {
                log.info("Goodreads: no book found for '{}'", name);
                return Optional.empty();
            }
        }
        JsonNode apolloState = fetchApolloState(bookId);
        if (apolloState == null) {
            log.info("Goodreads: could not parse book page for id={} (layout change or WAF)", bookId);
            return Optional.empty();
        }
        JsonNode book = findBook(apolloState, bookId);
        if (book == null) {
            return Optional.empty();
        }
        log.info("Goodreads: matched book {} ('{}') for series '{}'",
            bookId, text(book.get("title")), context.name());
        return Optional.of(toPatch(apolloState, book, bookId));
    }

    /** Resolves the best book id for a title via the JSON autocomplete endpoint (title-relevance ranked). */
    private String searchBookId(String name) {
        HttpUrl url = HttpUrl.get(AUTOCOMPLETE_URL).newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("q", name)
            .build();
        JsonNode items = getJson(url);
        if (items == null || !items.isArray() || items.isEmpty()) {
            return null;
        }
        // Score candidates by title relevance and require the shared threshold, so companion books and
        // study guides ("How to Draw …") are rejected rather than ranked first by the autocomplete.
        String best = null;
        double bestScore = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : items) {
            String id = autocompleteBookId(item);
            if (id == null || !seen.add(id)) {
                continue;
            }
            double score = Math.max(
                TitleMatch.score(name, text(item.get("title"))),
                TitleMatch.score(name, text(item.get("bookTitleBare"))));
            if (score > bestScore) {
                best = id;
                bestScore = score;
            }
        }
        if (bestScore < TitleMatch.DEFAULT_THRESHOLD) {
            log.info("Goodreads: no autocomplete result passed the title-match threshold for '{}'", name);
            return null;
        }
        return best;
    }

    /** The numeric book id from an autocomplete item ({@code bookId} field, else the {@code bookUrl}). */
    private static String autocompleteBookId(JsonNode item) {
        JsonNode direct = item.get("bookId");
        if (direct != null && !direct.isNull()) {
            String id = direct.asText("");
            if (id.matches("\\d+")) {
                return id;
            }
        }
        String url = text(item.get("bookUrl"));
        if (url != null) {
            Matcher m = BOOK_ID_IN_URL.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /** Fetches the book page and returns the {@code __NEXT_DATA__} Apollo state object, or null. */
    private JsonNode fetchApolloState(String bookId) {
        String html = getHtml(HttpUrl.get(BOOK_URL + bookId));
        if (html == null) {
            return null;
        }
        Element script = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__");
        if (script == null) {
            return null;
        }
        try {
            JsonNode state = MAPPER.readTree(script.data())
                .path("props").path("pageProps").path("apolloState");
            return state.isObject() ? state : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The {@code Book:kca:{id}} node with a title, else the first {@code Book:kca:} node. */
    private static JsonNode findBook(JsonNode state, String bookId) {
        JsonNode exact = state.get("Book:kca:" + bookId);
        if (exact != null && text(exact.get("title")) != null) {
            return exact;
        }
        return findByKeyPrefix(state, "Book:kca:");
    }

    private static JsonNode findByKeyPrefix(JsonNode state, String prefix) {
        for (Map.Entry<String, JsonNode> entry : state.properties()) {
            if (entry.getKey().startsWith(prefix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static SeriesMetadataPatch toPatch(JsonNode state, JsonNode book, String bookId) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();

        b.identifiers(Map.of(MetadataIdentifiers.GOODREADS, bookId));

        // Prefer the series title for series-level metadata; fall back to the book title (sans subtitle).
        String title = seriesTitle(state, book);
        if (title == null) {
            title = text(book.get("title"));
            if (title != null) {
                int colon = title.indexOf(':');
                if (colon > 0) {
                    title = title.substring(0, colon).strip();
                }
            }
        }
        if (title != null) {
            b.title(title);
        }

        String description = text(book.get("description"));
        if (description != null && !description.isBlank()) {
            b.summary(Jsoup.parse(description).wholeText().strip());
        }

        JsonNode details = book.path("details");
        String publisher = text(details.get("publisher"));
        if (publisher != null && !publisher.isBlank() && !"null".equals(publisher)) {
            b.publisher(publisher.strip());
        }
        String language = text(details.path("language").get("name"));
        if (language != null && !language.isBlank()) {
            b.language(language);
        }

        List<String> genres = new ArrayList<>(new LinkedHashSet<>(genreNames(book)));
        if (!genres.isEmpty()) {
            b.genres(genres);
        }

        String cover = text(book.get("imageUrl"));
        if (cover != null && !cover.isBlank()) {
            b.coverUrl(cover);
        }

        b.links(List.of(new WebLink("Goodreads", BOOK_URL + bookId)));
        return b.build();
    }

    /** The title of the book's first series, resolved through its Apollo {@code __ref} pointer. */
    private static String seriesTitle(JsonNode state, JsonNode book) {
        JsonNode first = book.path("bookSeries").path(0).path("series");
        String ref = text(first.get("__ref"));
        JsonNode series = ref != null ? state.get(ref) : findByKeyPrefix(state, "Series:kca");
        return series != null ? text(series.get("title")) : null;
    }

    private static List<String> genreNames(JsonNode book) {
        List<String> out = new ArrayList<>();
        for (JsonNode g : book.path("bookGenres")) {
            String genre = text(g.path("genre").get("name"));
            if (genre != null && !genre.isBlank()) {
                out.add(genre.strip());
            }
        }
        return out;
    }

    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asText() : null;
    }

    private JsonNode getJson(HttpUrl url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            throwIfRateLimited(res);
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                log.warn("Goodreads: HTTP {} for {}", res.code(), url.encodedPath());
                return null;
            }
            return MAPPER.readTree(body.string());
        } catch (ProviderRateLimitException e) {
            throw e; // must reach the core's cooldown handling — don't swallow with the IO failures below
        } catch (Exception e) {
            log.warn("Goodreads: request failed for {}", url.encodedPath(), e);
            return null; // fail soft, per the SPI contract
        }
    }

    private String getHtml(HttpUrl url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            throwIfRateLimited(res);
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                log.warn("Goodreads: HTTP {} for {}", res.code(), url.encodedPath());
                return null;
            }
            return body.string();
        } catch (ProviderRateLimitException e) {
            throw e; // must reach the core's cooldown handling — don't swallow with the IO failures below
        } catch (Exception e) {
            log.warn("Goodreads: request failed for {}", url.encodedPath(), e);
            return null; // fail soft, per the SPI contract
        }
    }

    /** Signals a 429 to the core so it cools this provider down instead of retrying it per item. */
    private static void throwIfRateLimited(Response res) {
        if (res.code() != 429) {
            return;
        }
        Long retryAfter = null;
        String header = res.header("Retry-After");
        if (header != null) {
            try {
                retryAfter = Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) {
                // date-form Retry-After — let the core use its configured default
            }
        }
        throw new ProviderRateLimitException("Goodreads rate limit (HTTP 429)", retryAfter);
    }
}
