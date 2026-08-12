package dev.kodex.plugin.dokiraw;

import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.content.ContentSource;
import dev.kodex.spi.content.SearchResult;
import dev.kodex.spi.content.SeriesPage;
import dev.kodex.spi.content.SeriesStatus;
import dev.kodex.spi.content.SourceChapter;
import dev.kodex.spi.content.SourcePage;
import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;
import dev.kodex.spi.common.http.HttpClientProvider;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.pf4j.Extension;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class DokirawSource implements ContentSource {

    // Domain moved 2026-08 (upstream b19b79d13, "Update domain for 9 extensions"); .cloud no longer resolves.
    private static final String BASE_URL = "https://dokiraw.work";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern DATE_NUMBER = Pattern.compile("(\\d+)");
    private static final int POPULAR_PER_PAGE = 36;
    private static final int RESULTS_PER_PAGE = 20;

    private static final String ITEM_SELECTOR = "div[class*=manga-item_item]";

    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.cloudflareClient() : FALLBACK;
    }

    @Override
    public String displayName() {
        return "Dokiraw";
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

    @Override
    public boolean supportsLatest() {
        return false;
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        HttpUrl url = HttpUrl.get(BASE_URL + "/hot").newBuilder()
            .addQueryParameter("page", String.valueOf(Math.max(1, page)))
            .build();
        return parseList(getHtml(url.toString()), POPULAR_PER_PAGE);
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/search/manga").newBuilder();
        if (query != null && !query.isBlank()) {
            url.addQueryParameter("keyword", query.trim());
        }
        String genre = selectedGenre(filters);
        if (genre != null) {
            url.addQueryParameter("genre", genre);
        }
        url.addQueryParameter("page", String.valueOf(Math.max(1, page)));
        return parseList(getHtml(url.build().toString()), RESULTS_PER_PAGE);
    }

    private SeriesPage parseList(Document doc, int perPage) {
        if (doc == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        var elements = doc.select(ITEM_SELECTOR);
        for (Element element : elements) {
            SearchResult r = toResult(element);
            if (r != null) {
                items.add(r);
            }
        }
        return new SeriesPage(items, elements.size() == perPage);
    }

    private SearchResult toResult(Element element) {
        Element anchor = element.selectFirst("a[href*=/manga/]");
        if (anchor == null) {
            return null;
        }
        String externalId = relative(anchor.attr("abs:href"));
        Element title = element.selectFirst("h3");
        Element img = element.selectFirst("img");
        String thumbnail = null;
        if (img != null) {
            thumbnail = img.attr("abs:data-original");
            if (thumbnail.isBlank()) {
                thumbnail = img.attr("abs:src");
            }
        }
        return new SearchResult(id(), externalId, title != null ? title.text() : externalId,
            null, thumbnail == null || thumbnail.isBlank() ? null : thumbnail,
            null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
    }

    // ---- Filters ---------------------------------------------------------------------------------

    @Override
    public FilterList getFilterList() {
        return new FilterList(List.of(
            new Filter.Header("Search by Genre"),
            new Filter.Select(Filters.GENRE, Filters.GENRES, 0)));
    }

    private static String selectedGenre(FilterList filters) {
        if (filters == null) {
            return null;
        }
        for (Filter<?> f : filters.filters()) {
            if (f instanceof Filter.Select select && Filters.GENRE.equals(select.name())) {
                int idx = select.state() == null ? 0 : select.state();
                if (idx > 0 && idx < Filters.GENRES.size()) {
                    return Filters.GENRES.get(idx);
                }
            }
        }
        return null;
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId);
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        Element titleEl = doc.selectFirst("div[class*=manga-detail_boxInfo] h1");
        String title = titleEl != null ? titleEl.text() : seriesExternalId;

        var descParas = doc.select("div.work-break p");
        String description = descParas.isEmpty() ? null : descParas.last().text();

        SeriesStatus status = SeriesStatus.UNKNOWN;
        if (doc.selectFirst("div:contains(連載中)") != null) {
            status = SeriesStatus.ONGOING;
        } else if (doc.selectFirst("div:contains(完結)") != null) {
            status = SeriesStatus.COMPLETED;
        }

        Element cover = doc.selectFirst("img[src*=cover]");
        String thumbnail = cover != null ? cover.attr("abs:src") : null;

        return new SearchResult(id(), seriesExternalId, title,
            description == null || description.isBlank() ? null : description,
            thumbnail == null || thumbnail.isBlank() ? null : thumbnail,
            null, null, List.of(), status, Map.of());
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId);
        List<SourceChapter> chapters = new ArrayList<>();
        if (doc == null) {
            return chapters;
        }
        for (Element a : doc.select("a:has(div[class*=manga-detail_chapter])")) {
            Element container = a.selectFirst("div[class*=manga-detail_chapter]");
            if (container == null) {
                continue;
            }
            var spans = container.select("span");
            String name = spans.isEmpty() ? a.text() : spans.first().text();
            LocalDate date = spans.size() > 1 ? parseDate(spans.get(1).text()) : null;
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
        var imgs = doc.select("div.page-chapter img");
        for (int i = 0; i < imgs.size(); i++) {
            Element img = imgs.get(i);
            String src = img.attr("abs:data-cdn");
            if (src.isBlank()) {
                src = img.attr("abs:data-original");
            }
            if (src.isBlank()) {
                src = img.attr("abs:src");
            }
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

    private Document getHtml(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                return null;
            }
            return Jsoup.parse(body.string(), url);
        } catch (Exception e) {
            return null;
        }
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

    private static Double parseNumber(String name) {
        if (name == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(name);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    /** Converts a Japanese relative date ("1日前", "昨日", "3週前", ...) to an absolute {@link LocalDate}. */
    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        LocalDate now = LocalDate.now();
        if (dateStr.contains("昨日")) {
            return now.minusDays(1);
        }
        Matcher m = DATE_NUMBER.matcher(dateStr);
        if (!m.find()) {
            return null;
        }
        int amount = Integer.parseInt(m.group(1));
        if (dateStr.contains("秒") || dateStr.contains("分") || dateStr.contains("時")) {
            return now; // sub-day granularity collapses to today
        }
        if (dateStr.contains("日")) {
            return now.minusDays(amount);
        }
        if (dateStr.contains("週")) {
            return now.minusWeeks(amount);
        }
        if (dateStr.contains("月")) {
            return now.minusMonths(amount);
        }
        if (dateStr.contains("年")) {
            return now.minusYears(amount);
        }
        return null;
    }
}
