package dev.kodex.ext.rawkuma;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.content.ContentSource;
import dev.kodex.spi.content.SearchResult;
import dev.kodex.spi.content.SeriesPage;
import dev.kodex.spi.content.SeriesStatus;
import dev.kodex.spi.content.SourceChapter;
import dev.kodex.spi.content.SourcePage;
import dev.kodex.spi.content.filter.FilterList;
import dev.kodex.spi.common.http.HttpClientProvider;
import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.pf4j.Extension;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class RawkumaSource implements ContentSource {

    private static final String BASE_URL = "https://rawkuma.net";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;
    private volatile String nonce;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.httpClient() : FALLBACK;
    }

    @Override
    public String displayName() {
        return "Rawkuma";
    }

    /** The upstream source sets {@code override val versionId = 2}; mirror it so the Mihon id matches. */
    @Override
    public int versionId() {
        return 2;
    }

    @Override
    public boolean adultContent() {
        return true;
    }

    @Override
    public String website() {
        return BASE_URL;
    }

    @Override
    public String language() {
        return "ja";
    }

    /** Mihon stores the manga url as a {@code {"id":..,"slug":".."}} JSON blob (or a {@code /manga/<slug>/} path). */
    @Override
    public String toSeriesExternalId(String mihonMangaUrl) {
        if (mihonMangaUrl == null) {
            return null;
        }
        if (mihonMangaUrl.startsWith("{")) {
            try {
                return MAPPER.readTree(mihonMangaUrl).path("slug").asText(mihonMangaUrl);
            } catch (Exception e) {
                return mihonMangaUrl;
            }
        }
        String slug = slugFromMangaUrl(mihonMangaUrl);
        return slug != null ? slug : mihonMangaUrl;
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return advancedSearch(page, "", "popular");
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return advancedSearch(page, "", "updated");
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        return advancedSearch(page, query == null ? "" : query.trim(), "popular");
    }

    private SeriesPage advancedSearch(int page, String query, String orderBy) {
        String nonceValue = nonce();
        if (nonceValue == null) {
            return SeriesPage.empty();
        }
        MultipartBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("nonce", nonceValue)
            .addFormDataPart("inclusion", "OR")
            .addFormDataPart("exclusion", "OR")
            .addFormDataPart("page", String.valueOf(Math.max(1, page)))
            .addFormDataPart("genre", "[]")
            .addFormDataPart("genre_exclude", "[]")
            .addFormDataPart("author", "[]")
            .addFormDataPart("artist", "[]")
            .addFormDataPart("project", "0")
            .addFormDataPart("type", "[]")
            .addFormDataPart("status", "[]")
            .addFormDataPart("order", "desc")
            .addFormDataPart("orderby", orderBy)
            .addFormDataPart("query", query)
            .build();

        String fragment = postForm(BASE_URL + "/wp-admin/admin-ajax.php?action=advanced_search", body);
        if (fragment == null) {
            return SeriesPage.empty();
        }
        Document doc = Jsoup.parseBodyFragment(fragment, BASE_URL);
        List<String> slugs = new ArrayList<>();
        for (Element a : doc.select("div > a[href*=/manga/]:has(> img)")) {
            String slug = slugFromMangaUrl(a.attr("abs:href"));
            if (slug != null && !slugs.contains(slug)) {
                slugs.add(slug);
            }
        }
        boolean hasNextPage = doc.selectFirst("button:has(svg)") != null;
        if (slugs.isEmpty()) {
            return new SeriesPage(List.of(), false);
        }

        Map<String, SearchResult> bySlug = fetchMangaBySlugs(slugs);
        List<SearchResult> items = new ArrayList<>();
        for (String slug : slugs) {
            SearchResult r = bySlug.get(slug);
            if (r != null) {
                items.add(r);
            }
        }
        return new SeriesPage(items, hasNextPage);
    }

    /** Resolves a batch of slugs to series via the WP REST API, dropping novels (unreadable here). */
    private Map<String, SearchResult> fetchMangaBySlugs(List<String> slugs) {
        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/wp-json/wp/v2/manga").newBuilder();
        for (String slug : slugs) {
            url.addQueryParameter("slug[]", slug);
        }
        url.addQueryParameter("per_page", String.valueOf(slugs.size() + 1));
        url.addQueryParameter("_embed", null);
        Map<String, SearchResult> bySlug = new LinkedHashMap<>();
        JsonNode arr = getJson(url.build().toString());
        if (arr == null || !arr.isArray()) {
            return bySlug;
        }
        for (JsonNode manga : arr) {
            if (getTerms(manga, "type").contains("Novel")) {
                continue;
            }
            String slug = manga.path("slug").asText(null);
            if (slug != null) {
                bySlug.put(slug, toResult(manga));
            }
        }
        return bySlug;
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        JsonNode manga = fetchMangaBySlug(seriesExternalId);
        if (manga == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        return toResult(manga);
    }

    private SearchResult toResult(JsonNode manga) {
        String slug = manga.path("slug").asText("");
        String title = Parser.unescapeEntities(manga.path("title").path("rendered").asText(slug), false);
        String description = Jsoup.parseBodyFragment(
            manga.path("content").path("rendered").asText("")).wholeText().trim();

        String thumbnail = null;
        JsonNode media = manga.path("_embedded").path("wp:featuredmedia");
        if (media.isArray() && !media.isEmpty()) {
            thumbnail = nullable(media.get(0).path("source_url"));
        }

        String author = String.join(", ", getTerms(manga, "series-author"));
        String artist = String.join(", ", getTerms(manga, "artist"));
        Set<String> genres = new LinkedHashSet<>(getTerms(manga, "genre"));
        genres.addAll(getTerms(manga, "type"));

        return new SearchResult(id(), slug, title,
            description.isBlank() ? null : description, thumbnail,
            author.isBlank() ? null : author, artist.isBlank() ? null : artist,
            new ArrayList<>(genres), parseStatus(getTerms(manga, "status")), Map.of());
    }

    private static SeriesStatus parseStatus(List<String> statusTerms) {
        if (statusTerms.contains("Ongoing")) {
            return SeriesStatus.ONGOING;
        }
        if (statusTerms.contains("Completed")) {
            return SeriesStatus.COMPLETED;
        }
        if (statusTerms.contains("Cancelled")) {
            return SeriesStatus.CANCELLED;
        }
        if (statusTerms.contains("On Hiatus")) {
            return SeriesStatus.ON_HIATUS;
        }
        return SeriesStatus.UNKNOWN;
    }

    /** A {@code wp:term} taxonomy's names (e.g. "genre", "status", "series-author"). */
    private static List<String> getTerms(JsonNode manga, String taxonomy) {
        List<String> names = new ArrayList<>();
        JsonNode termGroups = manga.path("_embedded").path("wp:term");
        if (!termGroups.isArray()) {
            return names;
        }
        for (JsonNode group : termGroups) {
            if (group.isArray() && !group.isEmpty()
                && taxonomy.equals(group.get(0).path("taxonomy").asText())) {
                for (JsonNode term : group) {
                    String name = nullable(term.path("name"));
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        JsonNode manga = fetchMangaBySlug(seriesExternalId);
        List<SourceChapter> chapters = new ArrayList<>();
        if (manga == null) {
            return chapters;
        }
        int mangaId = manga.path("id").asInt(-1);
        if (mangaId < 0) {
            return chapters;
        }
        // page=<high number> keeps the AJAX endpoint from hiding chapters behind its lazy-load threshold.
        HttpUrl url = HttpUrl.get(BASE_URL + "/wp-admin/admin-ajax.php").newBuilder()
            .addQueryParameter("manga_id", String.valueOf(mangaId))
            .addQueryParameter("page", "9999")
            .addQueryParameter("action", "chapter_list")
            .build();
        String fragment = getString(url.toString());
        if (fragment == null) {
            return chapters;
        }
        Document doc = Jsoup.parseBodyFragment(fragment, BASE_URL);
        for (Element a : doc.select("div a:has(time)")) {
            Element nameEl = a.selectFirst("span");
            String name = nameEl != null ? nameEl.ownText() : a.ownText();
            Element time = a.selectFirst("time");
            LocalDate date = time != null ? parseDate(time.attr("datetime")) : null;
            chapters.add(new SourceChapter(relative(a.attr("abs:href")), name,
                parseNumber(name), null, date, Map.of()));
        }
        return chapters;
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + chapterExternalId);
        List<SourcePage> pages = new ArrayList<>();
        if (doc == null) {
            return pages;
        }
        Map<String, String> headers = Map.of("Referer", BASE_URL + "/", "User-Agent", USER_AGENT);
        var imgs = doc.select("main .relative section > img");
        for (int i = 0; i < imgs.size(); i++) {
            String src = imgs.get(i).attr("abs:src");
            if (!src.isBlank()) {
                pages.add(new SourcePage(i, src, headers));
            }
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** The single WP-REST manga record for a slug (with embedded media/terms), or null. */
    private JsonNode fetchMangaBySlug(String slug) {
        HttpUrl url = HttpUrl.get(BASE_URL + "/wp-json/wp/v2/manga").newBuilder()
            .addQueryParameter("slug[]", slug)
            .addQueryParameter("_embed", null)
            .build();
        JsonNode arr = getJson(url.toString());
        return arr != null && arr.isArray() && !arr.isEmpty() ? arr.get(0) : null;
    }

    /** Lazily fetches and caches the theme's search nonce (required by {@code advanced_search}). */
    private String nonce() {
        String cached = nonce;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (nonce != null) {
                return nonce;
            }
            String body = getString(BASE_URL + "/wp-admin/admin-ajax.php?type=search_form&action=get_nonce");
            if (body == null) {
                return null;
            }
            Element input = Jsoup.parseBodyFragment(body).selectFirst("input[name=search_nonce]");
            String value = input != null ? input.attr("value") : null;
            if (value != null && !value.isBlank()) {
                nonce = value;
            }
            return nonce;
        }
    }

    private Document getHtml(String url) {
        String body = getString(url);
        return body == null ? null : Jsoup.parse(body, url);
    }

    private JsonNode getJson(String url) {
        String body = getString(url);
        if (body == null) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .get().build();
        return execute(req);
    }

    private String postForm(String url, RequestBody body) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .post(body).build();
        return execute(req);
    }

    private String execute(Request req) {
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                return null;
            }
            return body.string();
        } catch (Exception e) {
            return null; // fail soft, per the SPI contract
        }
    }

    /** The slug of a {@code /manga/<slug>/...} url (path segment after "manga"), or null. */
    private static String slugFromMangaUrl(String url) {
        try {
            List<String> segments = HttpUrl.get(url).pathSegments();
            int idx = segments.indexOf("manga");
            if (idx >= 0 && idx + 1 < segments.size()) {
                String slug = segments.get(idx + 1);
                return slug.isBlank() ? null : slug;
            }
        } catch (Exception ignored) {
            // not an absolute http url
        }
        return null;
    }

    private static String relative(String absUrl) {
        try {
            URI u = URI.create(absUrl);
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            return u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
        } catch (Exception e) {
            return absUrl;
        }
    }

    private static String nullable(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asText() : null;
    }

    private static Double parseNumber(String name) {
        if (name == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(name);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static LocalDate parseDate(String iso) {
        try {
            return iso != null && iso.length() >= 10 ? LocalDate.parse(iso.substring(0, 10)) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
