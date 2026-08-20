package dev.kodex.plugin.novelbuddy;

import dev.kodex.spi.MediaKind;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.content.ContentSource;
import dev.kodex.spi.content.SearchResult;
import dev.kodex.spi.content.SeriesPage;
import dev.kodex.spi.content.SeriesStatus;
import dev.kodex.spi.content.SourceChapter;
import dev.kodex.spi.content.SourceChapterContent;
import dev.kodex.spi.content.SourcePage;
import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.pf4j.Extension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NovelBuddy (novelbuddy.me) — a web-novel source. Browse and search come from the site's JSON API at
 * {@code api.novelbuddy.me}; series details and the chapter-text fallback are read out of the
 * {@code __NEXT_DATA__} blob the Next.js frontend embeds in each page.
 *
 * <p>Ported from the LNReader {@code novelbuddy} plugin.
 */
@Extension
public class NovelBuddySource implements ContentSource {

    private static final String BASE_URL = "https://novelbuddy.me";
    private static final String API_URL = "https://api.novelbuddy.me";
    private static final int PAGE_SIZE = 24;
    /** The site caps chapter-count filters at this value; anything else is dropped as upstream does. */
    private static final int MAX_CHAPTER_FILTER = 10000;
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(NovelBuddySource.class.getName());

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    /** novelbuddy.me sits behind Cloudflare, so go through the operator's solver when one is configured. */
    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.cloudflareClient() : FALLBACK;
    }

    @Override
    public MediaKind kind() {
        return MediaKind.BOOK;
    }

    @Override
    public String displayName() {
        return "NovelBuddy";
    }

    @Override
    public String website() {
        return BASE_URL;
    }

    @Override
    public String language() {
        return "en";
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return browse(null, page, Filters.defaultFilterList(Filters.DEFAULT_ORDER));
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return browse(null, page, Filters.defaultFilterList("latest"));
    }

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList(Filters.DEFAULT_ORDER);
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        FilterList effective = (filters == null || filters.filters().isEmpty())
            ? Filters.defaultFilterList(Filters.DEFAULT_ORDER)
            : filters;
        return browse(query, page, effective);
    }

    private SeriesPage browse(String query, int page, FilterList filters) {
        HttpUrl.Builder url = HttpUrl.get(API_URL + "/titles/search").newBuilder();
        List<String> includedGenres = new ArrayList<>();
        List<String> excludedGenres = new ArrayList<>();
        List<String> demographics = new ArrayList<>();

        for (Filter<?> filter : filters.filters()) {
            if (filter instanceof Filter.Group group) {
                if (Filters.GENRES.equals(group.name())) {
                    for (Filter<?> option : group.state()) {
                        if (!(option instanceof Filter.TriState tri)) {
                            continue;
                        }
                        String value = Filters.valueOf(Filters.GENRES, tri.name());
                        if (value == null) {
                            continue;
                        }
                        if (tri.isIncluded()) {
                            includedGenres.add(value);
                        } else if (tri.isExcluded()) {
                            excludedGenres.add(value);
                        }
                    }
                } else if (Filters.DEMOGRAPHICS.equals(group.name())) {
                    for (Filter<?> option : group.state()) {
                        if (option instanceof Filter.CheckBox box && Boolean.TRUE.equals(box.state())) {
                            String value = Filters.valueOf(Filters.DEMOGRAPHICS, box.name());
                            if (value != null) {
                                demographics.add(value);
                            }
                        }
                    }
                }
            } else if (filter instanceof Filter.Select select) {
                String value = Filters.selectedValue(select);
                if (value == null) {
                    continue;
                }
                if (Filters.ORDER_BY.equals(select.name())) {
                    url.addQueryParameter("sort", value);
                } else if (Filters.STATUS.equals(select.name()) && !"all".equals(value)) {
                    url.addQueryParameter("status", value);
                }
            } else if (filter instanceof Filter.TextFilter text) {
                String bounded = boundedCount(text.state());
                if (bounded == null) {
                    continue;
                }
                if (Filters.MIN_CHAPTERS.equals(text.name())) {
                    url.addQueryParameter("min_ch", bounded);
                } else if (Filters.MAX_CHAPTERS.equals(text.name())) {
                    url.addQueryParameter("max_ch", bounded);
                }
            }
        }

        if (!includedGenres.isEmpty()) {
            url.addQueryParameter("genres", String.join(",", includedGenres));
        }
        if (!excludedGenres.isEmpty()) {
            url.addQueryParameter("exclude", String.join(",", excludedGenres));
        }
        if (!demographics.isEmpty()) {
            url.addQueryParameter("demographic", String.join(",", demographics));
        }
        if (query != null && !query.isBlank()) {
            url.addQueryParameter("q", query.trim());
        }
        url.addQueryParameter("page", String.valueOf(Math.max(1, page)));
        url.addQueryParameter("limit", String.valueOf(PAGE_SIZE));

        JsonNode root = getJson(url.build().toString());
        if (root == null) {
            return SeriesPage.empty();
        }
        JsonNode items = root.path("data").path("items");
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode item : items) {
            String path = novelPath(text(item.get("url")));
            if (path == null) {
                continue;
            }
            results.add(new SearchResult(id(), path, orElse(text(item.get("name")), path), null,
                text(item.get("cover")), null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        // The API reports no total, so assume another page whenever this one came back full.
        return new SeriesPage(results, items.size() >= PAGE_SIZE);
    }

    /** Upstream only forwards whole chapter counts within the site's accepted range. */
    private static String boundedCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 && value <= MAX_CHAPTER_FILTER ? Integer.toString(value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        JsonNode manga = initialManga(seriesExternalId);
        if (manga == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }

        List<String> genres = new ArrayList<>();
        for (JsonNode genre : manga.path("genres")) {
            String name = text(genre.get("name"));
            if (name != null) {
                genres.add(name);
            }
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        JsonNode rating = manga.path("ratingStats").get("average");
        if (rating != null && rating.isNumber()) {
            attributes.put("rating", rating.asString());
        }

        return new SearchResult(id(), seriesExternalId,
            orElse(text(manga.get("name")), seriesExternalId),
            plainSummary(text(manga.get("summary"))),
            text(manga.get("cover")),
            joinNames(manga.path("authors")),
            joinNames(manga.path("artists")),
            genres,
            parseStatus(text(manga.get("status"))),
            attributes);
    }

    /** The Next.js page state, which carries everything the details view needs in one blob. */
    private JsonNode initialManga(String seriesExternalId) {
        JsonNode data = nextData(BASE_URL + "/" + stripLeadingSlash(seriesExternalId));
        if (data == null) {
            return null;
        }
        JsonNode manga = data.path("props").path("pageProps").path("initialManga");
        return manga.isMissingNode() || manga.isNull() ? null : manga;
    }

    private JsonNode nextData(String url) {
        Document doc = getHtml(url);
        if (doc == null) {
            return null;
        }
        Element script = doc.getElementById("__NEXT_DATA__");
        if (script == null) {
            LOG.log(System.Logger.Level.WARNING, () -> "NovelBuddy: no __NEXT_DATA__ payload at " + url);
            return null;
        }
        try {
            return MAPPER.readTree(script.data());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "NovelBuddy: unreadable __NEXT_DATA__ at " + url, e);
            return null;
        }
    }

    /** The summary is an HTML fragment; flatten it to the paragraph-separated text the UI shows. */
    private static String plainSummary(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Document doc = Jsoup.parseBodyFragment(html);
        doc.select("br").append("\n");
        doc.select("p").prepend("\n").append("\n");
        List<String> lines = new ArrayList<>();
        for (String line : doc.body().wholeText().split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines.isEmpty() ? null : String.join("\n\n", lines);
    }

    private static String joinNames(JsonNode array) {
        List<String> names = new ArrayList<>();
        for (JsonNode entry : array) {
            String name = text(entry.get("name"));
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private static SeriesStatus parseStatus(String status) {
        if (status == null) {
            return SeriesStatus.UNKNOWN;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "ongoing" -> SeriesStatus.ONGOING;
            case "completed" -> SeriesStatus.COMPLETED;
            case "hiatus" -> SeriesStatus.ON_HIATUS;
            case "dropped", "cancelled" -> SeriesStatus.CANCELLED;
            default -> SeriesStatus.UNKNOWN;
        };
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        JsonNode manga = initialManga(seriesExternalId);
        if (manga == null) {
            return List.of();
        }
        String mangaId = text(manga.get("id"));
        if (mangaId == null) {
            return List.of();
        }

        // The chapter list lives behind the API, keyed by the content version the page state carries.
        String contentVersion = text(manga.has("content_version") ? manga.get("content_version") : manga.get("cv"));
        String url = API_URL + "/titles/" + mangaId + "/chapters"
            + (contentVersion == null ? "" : "?cv=" + contentVersion);
        JsonNode root = getJson(url);

        JsonNode chapters = root == null ? null : root.path("data").path("chapters");
        boolean apiUsable = root != null && root.path("success").asBoolean(false)
            && chapters != null && chapters.isArray() && !chapters.isEmpty();
        // Older titles ship their chapter list inline instead of exposing it on the API.
        JsonNode source = apiUsable ? chapters : manga.path("chapters");

        List<SourceChapter> result = new ArrayList<>();
        for (JsonNode chapter : source) {
            String path = novelPath(text(chapter.get("url")));
            String chapterId = text(chapter.get("id"));
            if (path == null) {
                continue;
            }
            String externalId = chapterId == null
                ? path
                : path + "?id=" + mangaId + "&chapterId=" + chapterId;
            String released = text(chapter.has("updated_at") ? chapter.get("updated_at") : chapter.get("updatedAt"));
            result.add(new SourceChapter(externalId, text(chapter.get("name")), null, null,
                parseDate(released), Map.of()));
        }
        // Both feeds arrive newest-first; the core wants oldest-first reading order.
        Collections.reverse(result);
        return result;
    }

    // ---- Content ---------------------------------------------------------------------------------

    /** BOOK sources serve text, not page images. */
    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        return List.of();
    }

    @Override
    public SourceChapterContent chapterContent(String chapterExternalId, ProviderSettings settings) {
        String novelId = queryParam(chapterExternalId, "id");
        String chapterId = queryParam(chapterExternalId, "chapterId");
        String title = null;
        String content = null;

        if (novelId != null && chapterId != null) {
            JsonNode root = getJson(API_URL + "/titles/" + novelId + "/chapters/" + chapterId);
            if (root != null) {
                JsonNode chapter = root.path("data").path("chapter");
                content = text(chapter.get("content"));
                title = text(chapter.get("name"));
            }
        }

        if (content == null || content.isBlank()) {
            // Fall back to the rendered page's own state when the API declines to serve the chapter.
            JsonNode data = nextData(BASE_URL + "/" + stripLeadingSlash(chapterExternalId));
            if (data != null) {
                JsonNode chapter = data.path("props").path("pageProps").path("initialChapter");
                content = text(chapter.get("content"));
                if (title == null) {
                    title = text(chapter.get("name"));
                }
            }
        }

        return new SourceChapterContent(title, Watermark.strip(content));
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** Paths are stored without a leading slash, matching what the API and upstream plugin emit. */
    private static String novelPath(String url) {
        return url == null || url.isBlank() ? null : stripLeadingSlash(url);
    }

    private static String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static String queryParam(String path, String name) {
        int question = path.indexOf('?');
        if (question < 0) {
            return null;
        }
        for (String pair : path.substring(question + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private Document getHtml(String url) {
        try (Response res = execute(url, "text/html,application/xhtml+xml,*/*")) {
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            if (!res.isSuccessful() || payload == null) {
                int code = res.code();
                LOG.log(System.Logger.Level.WARNING, () -> "NovelBuddy HTTP " + code + " for " + url);
                return null;
            }
            return Jsoup.parse(payload, url);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "NovelBuddy request failed for " + url, e);
            return null; // fail soft, per the SPI contract
        }
    }

    private JsonNode getJson(String url) {
        try (Response res = execute(url, "application/json")) {
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            if (!res.isSuccessful() || payload == null) {
                int code = res.code();
                LOG.log(System.Logger.Level.WARNING, () -> "NovelBuddy HTTP " + code + " for " + url);
                return null;
            }
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "NovelBuddy request failed for " + url, e);
            return null; // fail soft, per the SPI contract
        }
    }

    private Response execute(String url, String accept) throws java.io.IOException {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .header("Accept", accept)
            .get().build();
        return http().newCall(req).execute();
    }

    /** A scalar JSON node's text, or null when the field is absent/null/non-scalar. */
    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asString() : null;
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static LocalDate parseDate(String iso) {
        try {
            return iso != null && iso.length() >= 10 ? LocalDate.parse(iso.substring(0, 10)) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
