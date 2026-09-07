package dev.kodex.plugin.webnovel;

import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.content.ContentSource;
import dev.kodex.spi.content.SearchResult;
import dev.kodex.spi.content.SeriesPage;
import dev.kodex.spi.content.SeriesStatus;
import dev.kodex.spi.content.SourceChapter;
import dev.kodex.spi.content.SourcePage;
import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;
import dev.kodex.spi.common.http.SourceUnavailableException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.pf4j.Extension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebNovel (webnovel.com) — the site's <em>comics</em> catalogue, served by its JSON API under
 * {@code /go/pcm}.
 *
 * <p>Every API call must carry the {@code _csrfToken} the site hands out as a cookie, repeated as a
 * query parameter; {@link #csrfToken()} picks one up from the home page and caches it. Upstream tells
 * the user to open a WebView to get that cookie, but a plain request is enough — no browser needed.
 *
 * <p>Ported from the Keiyoushi {@code WebNovel} extension.
 */
@Extension
public class WebNovelComicSource implements ContentSource {

    private static final String BASE_URL = "https://www.webnovel.com";
    private static final String API_URL = BASE_URL + "/go/pcm";
    /** Covers and page images live on sibling hosts of the main site. */
    private static final String COVER_URL = "https://book-pic.webnovel.com";
    /** Per-chapter upload times, more accurate than the relative strings the API returns. */
    private static final String UPLOAD_TIMES_URL = "https://keiyoushi.github.io/webnovel-upload-time";
    private static final String CSRF_COOKIE = "_csrfToken";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(WebNovelComicSource.class.getName());

    private static final Pattern DIGITS = Pattern.compile("(\\d+)");
    /** Matches the cookie in a {@code Set-Cookie} header. */
    private static final Pattern CSRF_IN_COOKIE =
        Pattern.compile(Pattern.quote(CSRF_COOKIE) + "=([^;]+)");

    /** Publication states {@code actionStatus} can report. */
    private static final int STATUS_ONGOING = 1;
    private static final int STATUS_COMPLETED = 2;
    private static final int STATUS_ON_HIATUS = 3;

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    private volatile String csrfToken;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.httpClient() : FALLBACK;
    }

    /**
     * Upstream pins this source's id rather than deriving it from the name, so it is hard-coded here too
     * — deriving it would produce a different value and break {@code .tachibk} imports.
     */
    @Override
    public String id() {
        return "4081135203808920563";
    }

    @Override
    public String displayName() {
        return "WebNovel";
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
    public FilterList getFilterList() {
        return ComicFilters.defaultFilterList(ComicFilters.SORT_POPULAR);
    }

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return browse(page, ComicFilters.defaultFilterList(ComicFilters.SORT_POPULAR));
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return browse(page, ComicFilters.defaultFilterList(ComicFilters.SORT_LATEST));
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            FilterList effective = (filters == null || filters.filters().isEmpty())
                ? getFilterList()
                : filters;
            return browse(page, effective);
        }
        // A keyword uses the site's search endpoint, which ignores the browse filters.
        JsonNode data = api("/search/result", Map.of(
            "type", "manga",
            "pageIndex", String.valueOf(Math.max(1, page)),
            "keywords", q));
        return toSeriesPage(data.path("comicInfo"));
    }

    private SeriesPage browse(int page, FilterList filters) {
        String sort = ComicFilters.SORT_POPULAR;
        String status = "";
        String genre = "";
        for (Filter<?> filter : filters.filters()) {
            if (!(filter instanceof Filter.Select select)) {
                continue;
            }
            String value = ComicFilters.selectedValue(select);
            if (value == null) {
                continue;
            }
            switch (select.name()) {
                case ComicFilters.SORT_BY -> sort = value;
                case ComicFilters.CONTENT_STATUS -> status = value;
                case ComicFilters.GENRE -> genre = value;
                default -> {
                }
            }
        }

        JsonNode data = api("/category/categoryAjax", Map.of(
            "categoryType", "2", // 2 = comics
            "pageIndex", String.valueOf(Math.max(1, page)),
            "categoryId", genre,
            "bookStatus", status,
            "orderBy", sort));
        return toSeriesPage(data);
    }

    /**
     * Both browse shapes carry a list plus an {@code isLast} flag, but name the list differently: the
     * search endpoint returns {@code comicItems}, the category endpoint {@code items}.
     */
    private SeriesPage toSeriesPage(JsonNode listing) {
        JsonNode rows = listing.has("comicItems") ? listing.path("comicItems") : listing.path("items");
        List<SearchResult> items = new ArrayList<>();
        for (JsonNode item : rows) {
            // The search endpoint calls the id "comicId"; the category endpoint calls it "bookId".
            String comicId = text(item.has("comicId") ? item.get("comicId") : item.get("bookId"));
            String title = text(item.get("bookName"));
            if (comicId == null || title == null || title.isBlank()) {
                continue;
            }
            long coverUpdatedAt = item.has("CV")
                ? item.path("CV").asLong(0)
                : item.path("coverUpdateTime").asLong(0);
            List<String> genres = genreList(text(item.get("categoryName")));
            items.add(new SearchResult(id(), comicId, title,
                emptyToNull(text(item.get("description"))),
                coverUrl(comicId, coverUpdatedAt),
                emptyToNull(text(item.get("authorName"))), null,
                genres, SeriesStatus.UNKNOWN, Map.of()));
        }
        return new SeriesPage(items, listing.path("isLast").asInt(1) == 0);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        JsonNode data = api("/comic/getComicDetailPage", Map.of("comicId", seriesExternalId));
        if (data == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        JsonNode comic = data.path("comicInfo");

        int status = comic.path("actionStatus").asInt(0);
        StringBuilder description = new StringBuilder(orElse(text(comic.get("description")), ""));
        String updateCycle = text(comic.get("updateCycle"));
        // An ongoing comic advertises its release cadence; keep it as a trailing note like upstream.
        if (status == STATUS_ONGOING && updateCycle != null && !updateCycle.isBlank()) {
            description.append("\n\nInformation:\n• ")
                .append(Character.toUpperCase(updateCycle.charAt(0)))
                .append(updateCycle.substring(1));
        }

        return new SearchResult(id(), seriesExternalId,
            orElse(text(comic.get("comicName")), seriesExternalId),
            description.isEmpty() ? null : description.toString(),
            coverUrl(seriesExternalId, comic.path("CV").asLong(0)),
            emptyToNull(text(comic.get("authorName"))), null,
            genreList(text(comic.get("categoryName"))),
            switch (status) {
                case STATUS_ONGOING -> SeriesStatus.ONGOING;
                case STATUS_COMPLETED -> SeriesStatus.COMPLETED;
                case STATUS_ON_HIATUS -> SeriesStatus.ON_HIATUS;
                default -> SeriesStatus.UNKNOWN;
            },
            Map.of());
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        JsonNode data = api("/comic/getChapterList", Map.of("comicId", seriesExternalId));
        if (data == null) {
            return List.of();
        }
        String comicId = orElse(text(data.path("comicInfo").get("comicId")), seriesExternalId);

        // The API only reports relative publish times ("3 days ago"); Keiyoushi publishes exact ones.
        Map<String, Long> exactTimes = uploadTimes(comicId);

        List<SourceChapter> chapters = new ArrayList<>();
        for (JsonNode chapter : data.path("comicChapters")) {
            String chapterId = text(chapter.get("chapterId"));
            if (chapterId == null) {
                continue;
            }
            // Chapters above the reader's privilege tier aren't listed at all.
            if (chapter.path("userLevel").asInt(0) < chapter.path("chapterLevel").asInt(0)) {
                continue;
            }
            boolean premium = chapter.path("isVip").asInt(0) != 0 || chapter.path("price").asInt(0) != 0;
            boolean unlocked = chapter.path("isAuth").asInt(0) == 1;
            String name = orElse(text(chapter.get("chapterName")), chapterId);
            if (premium && !unlocked) {
                name = "🔒 " + name; // 🔒 — readable only with a paid unlock
            }

            Long exact = exactTimes.get(chapterId);
            LocalDate released = exact != null
                ? java.time.Instant.ofEpochMilli(exact).atZone(ZoneOffset.UTC).toLocalDate()
                : relativeDate(text(chapter.get("publishTime")));

            chapters.add(new SourceChapter(comicId + ":" + chapterId, name, null, null, released, Map.of()));
        }
        // The API already lists oldest first, which is the reading order the core wants. (Upstream
        // reverses here only because Mihon shows chapter lists newest-first.)
        return chapters;
    }

    private Map<String, Long> uploadTimes(String comicId) {
        String payload = get(UPLOAD_TIMES_URL + "/" + comicId + ".json", "application/json", null);
        if (payload == null) {
            return Map.of();
        }
        try {
            Map<String, Long> times = new HashMap<>();
            JsonNode root = MAPPER.readTree(payload);
            root.propertyStream().forEach(e -> times.put(e.getKey(), e.getValue().asLong(0)));
            return times;
        } catch (Exception e) {
            return Map.of(); // the side-car is a convenience, not a requirement
        }
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        int colon = chapterExternalId.indexOf(':');
        if (colon < 0) {
            return List.of();
        }
        String comicId = chapterExternalId.substring(0, colon);
        String chapterId = chapterExternalId.substring(colon + 1);

        // A large width asks for the highest resolution the site will serve anonymously.
        JsonNode data = api("/comic/getContent", Map.of(
            "comicId", comicId,
            "chapterId", chapterId,
            "width", "9999"));
        if (data == null) {
            return List.of();
        }
        Map<String, String> headers = Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
        List<SourcePage> pages = new ArrayList<>();
        int index = 0;
        for (JsonNode page : data.path("chapterInfo").path("chapterPage")) {
            String url = text(page.get("url"));
            if (url != null && !url.isBlank()) {
                pages.add(new SourcePage(index++, url, headers));
            }
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /**
     * Calls a {@code /go/pcm} endpoint and unwraps its {@code {code, data, msg}} envelope. A non-zero
     * code usually means the CSRF token went stale, so the token is dropped and the call retried once.
     */
    private JsonNode api(String path, Map<String, String> params) {
        String lastError = "no response";
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = csrfToken();
            HttpUrl.Builder url = HttpUrl.get(API_URL + path).newBuilder();
            params.forEach(url::addQueryParameter);
            url.addQueryParameter(CSRF_COOKIE, token);

            String payload = get(url.build().toString(), "application/json", token);
            JsonNode root;
            try {
                root = MAPPER.readTree(payload);
            } catch (Exception e) {
                throw SourceUnavailableException.unreadable(displayName(), API_URL + path, "response was not JSON");
            }
            int code = root.path("code").asInt(-1);
            if (code == 0) {
                return root.path("data");
            }
            lastError = "API error " + code + (text(root.get("msg")) == null ? "" : " — " + text(root.get("msg")));
            csrfToken = null; // most likely an expired token; fetch a fresh one and retry
        }
        // Two attempts with a fresh CSRF token both rejected — report it rather than showing an empty
        // shelf, which would read as "WebNovel has no comics".
        throw SourceUnavailableException.unreadable(displayName(), API_URL + path, lastError);
    }

    /**
     * The site sets {@code _csrfToken} on any page load and expects it echoed back as a query parameter.
     * One home-page request is enough to obtain it; the value is cached until a call rejects it.
     */
    private String csrfToken() {
        String cached = csrfToken;
        if (cached != null) {
            return cached;
        }
        Request req = new Request.Builder()
            .url(BASE_URL + "/")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            for (String cookie : res.headers("set-cookie")) {
                Matcher m = CSRF_IN_COOKIE.matcher(cookie);
                if (m.find()) {
                    String token = m.group(1);
                    csrfToken = token;
                    return token;
                }
            }
            throw SourceUnavailableException.unreadable(displayName(), BASE_URL + "/",
                "no " + CSRF_COOKIE + " cookie on the home page");
        } catch (RuntimeException e) {
            throw e; // already the right failure (unavailable / rate limit)
        } catch (Exception e) {
            throw SourceUnavailableException.transport(displayName(), BASE_URL + "/", e);
        }
    }

    private String get(String url, String accept, String token) {
        Request.Builder req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .header("Accept", accept);
        if (token != null) {
            // The server cross-checks the query parameter against the cookie.
            req.header("Cookie", CSRF_COOKIE + "=" + token);
        }
        try (Response res = http().newCall(req.get().build()).execute()) {
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            if (!res.isSuccessful() || payload == null) {
                throw SourceUnavailableException.http(displayName(), url, res.code(), payload);
            }
            return payload;
        } catch (RuntimeException e) {
            throw e; // already the right failure (unavailable / rate limit)
        } catch (Exception e) {
            throw SourceUnavailableException.transport(displayName(), url, e);
        }
    }

    /** Covers are addressed by comic id, with the cover's update time busting the CDN cache. */
    private static String coverUrl(String comicId, long coverUpdatedAt) {
        return COVER_URL + "/bookcover/" + comicId
            + "?imageId=" + coverUpdatedAt + "&imageMogr2/thumbnail/1024x";
    }

    private static List<String> genreList(String categoryName) {
        return categoryName == null || categoryName.isBlank() ? List.of() : List.of(categoryName);
    }

    /** Publish times arrive as prose like "3 days ago"; only day-and-coarser units move the date. */
    private static LocalDate relativeDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("now")) {
            return today;
        }
        Matcher m = DIGITS.matcher(lower);
        if (!m.find()) {
            return null;
        }
        int amount = Integer.parseInt(m.group(1));
        if (lower.contains("year")) {
            return today.minusYears(amount);
        }
        if (lower.contains("month")) {
            return today.minusMonths(amount);
        }
        if (lower.contains("day")) {
            return today.minusDays(amount);
        }
        if (lower.contains("hour") || lower.contains("minute")) {
            return today;
        }
        return null;
    }

    /** A scalar JSON node's text, or null when the field is absent/null/non-scalar. */
    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asString() : null;
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
