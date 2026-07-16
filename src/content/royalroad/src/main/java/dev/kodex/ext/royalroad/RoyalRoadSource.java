package dev.kodex.ext.royalroad;

import dev.kodex.spi.MediaKind;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.content.ContentSource;
import dev.kodex.spi.content.SearchResult;
import dev.kodex.spi.content.SeriesPage;
import dev.kodex.spi.content.SeriesStatus;
import dev.kodex.spi.content.SourceChapter;
import dev.kodex.spi.content.SourceChapterContent;
import dev.kodex.spi.content.SourcePage;
import dev.kodex.spi.content.filter.FilterList;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.common.http.ProviderRateLimitException;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class RoyalRoadSource implements ContentSource {

    private static final String BASE_URL = "https://www.royalroad.com";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Pattern HIDDEN_CLASS =
        Pattern.compile("\\.([A-Za-z0-9_-]+)\\s*\\{[^{}]*display:\\s*none", Pattern.CASE_INSENSITIVE);

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
    public MediaKind kind() {
        return MediaKind.BOOK;
    }

    @Override
    public String displayName() {
        return "Royal Road";
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
        return listPage("/fictions/best-rated", page);
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return listPage("/fictions/latest-updates", page);
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/fictions/search").newBuilder();
        url.addQueryParameter("page", String.valueOf(Math.max(1, page)));
        if (query != null && !query.isBlank()) {
            url.addQueryParameter("title", query.trim());
        }
        if (filters != null && !filters.filters().isEmpty()) {
            Filters.applyToUrl(url, filters);
        }
        return parseList(getHtml(url.build().toString()), Math.max(1, page));
    }

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList();
    }

    private SeriesPage listPage(String path, int page) {
        HttpUrl url = HttpUrl.get(BASE_URL + path).newBuilder()
            .addQueryParameter("page", String.valueOf(Math.max(1, page)))
            .build();
        return parseList(getHtml(url.toString()), Math.max(1, page));
    }

    private SeriesPage parseList(Document doc, int page) {
        if (doc == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        for (Element item : doc.select(".fiction-list-item")) {
            Element titleA = item.selectFirst("h2.fiction-title a");
            if (titleA == null) {
                titleA = item.selectFirst("a[href*=/fiction/]");
            }
            if (titleA == null) {
                continue;
            }
            String seriesId = toSeriesPath(titleA.attr("href"));
            if (seriesId == null) {
                continue;
            }
            String title = titleA.text().trim();
            Element img = item.selectFirst("img");
            String cover = img != null ? absUrl(firstNonBlank(img.attr("src"), img.attr("data-src"))) : null;
            items.add(new SearchResult(id(), seriesId, title, null, cover,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        // A next page exists if pagination links to it (robust across the list and search layouts).
        boolean hasNext = doc.select("ul.pagination a").stream()
            .anyMatch(a -> a.attr("href").contains("page=" + (page + 1)));
        return new SeriesPage(items, hasNext);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + "/" + strip(seriesExternalId));
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        Element h1 = doc.selectFirst("h1");
        String title = h1 != null ? h1.text().trim() : seriesExternalId;
        Element img = doc.selectFirst("img.thumbnail");
        if (img == null) {
            img = doc.selectFirst(".cover-art-container img, .fic-header img");
        }
        String cover = img != null ? absUrl(firstNonBlank(img.attr("src"), img.attr("data-src"))) : null;
        Element authorA = doc.selectFirst("a[href^=/profile/], h4 a[href*=/profile/]");
        String author = authorA != null ? authorA.text().trim() : null;
        Element descEl = doc.selectFirst("div.description");
        String description = descEl != null ? descEl.wholeText().trim() : null;
        List<String> genres = doc.select("span.tags a.fiction-tag, a.fiction-tag, span.tags a").eachText()
            .stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        SeriesStatus status = parseStatus(doc);
        return new SearchResult(id(), seriesExternalId, title,
            description == null || description.isBlank() ? null : description, cover,
            author, null, genres, status, Map.of());
    }

    private static SeriesStatus parseStatus(Document doc) {
        for (Element label : doc.select("span.label")) {
            String text = label.text().trim().toUpperCase(Locale.ROOT);
            switch (text) {
                case "ONGOING":
                    return SeriesStatus.ONGOING;
                case "COMPLETED":
                    return SeriesStatus.COMPLETED;
                case "HIATUS":
                    return SeriesStatus.ON_HIATUS;
                case "DROPPED":
                    return SeriesStatus.CANCELLED;
                default:
                    break;
            }
        }
        return SeriesStatus.UNKNOWN;
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + "/" + strip(seriesExternalId));
        List<SourceChapter> chapters = new ArrayList<>();
        if (doc == null) {
            return chapters;
        }
        var rows = doc.select("table#chapters tbody tr");
        int total = rows.size();
        int index = 0;
        for (Element tr : rows) {
            Element a = tr.selectFirst("td a[href*=/chapter/]");
            if (a == null) {
                continue;
            }
            String chapterPath = toChapterPath(a.attr("href"));
            if (chapterPath == null) {
                continue;
            }
            String name = a.text().trim();
            LocalDate date = null;
            Element time = tr.selectFirst("time[datetime]");
            if (time != null) {
                date = parseDate(time.attr("datetime"));
            }
            // Rows are listed oldest→newest top-to-bottom; assign ascending chapter numbers.
            chapters.add(new SourceChapter(chapterPath, name, (double) (++index), null, date, Map.of()));
        }
        return chapters;
    }

    // ---- Content (BOOK) --------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        return List.of(); // BOOK source: text comes from chapterContent
    }

    @Override
    public SourceChapterContent chapterContent(String chapterExternalId, ProviderSettings settings) {
        String html = getHtmlString(BASE_URL + "/" + strip(chapterExternalId));
        if (html == null) {
            return new SourceChapterContent(null, "");
        }
        Document doc = Jsoup.parse(html, BASE_URL);
        Element content = doc.selectFirst("div.chapter-content");
        if (content == null) {
            return new SourceChapterContent(null, "");
        }
        // Royal Road inserts hidden decoy paragraphs (a class set to display:none in an inline <style>) to
        // trip up scrapers; remove anything carrying that class before reading the prose.
        Matcher m = HIDDEN_CLASS.matcher(html);
        if (m.find()) {
            content.select("." + m.group(1)).remove();
        }
        Element h1 = doc.selectFirst("h1");
        String title = h1 != null ? h1.text().trim() : null;
        return new SourceChapterContent(title, content.html());
    }

    // ---- HTTP / helpers --------------------------------------------------------------------------

    private Document getHtml(String url) {
        String body = getHtmlString(url);
        return body == null ? null : Jsoup.parse(body, url);
    }

    private String getHtmlString(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            throwIfRateLimited(res);
            ResponseBody respBody = res.body();
            if (!res.isSuccessful() || respBody == null) {
                return null;
            }
            return respBody.string();
        } catch (ProviderRateLimitException e) {
            throw e; // must reach the core's retry/backoff handling — don't swallow with the IO failures below
        } catch (Exception e) {
            return null; // fail soft, per the SPI contract
        }
    }

    /** Signals a 429 to the core so the download worker backs off and retries instead of failing the chapter. */
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
                // date-form Retry-After — let the core use its default backoff
            }
        }
        throw new ProviderRateLimitException("Royal Road rate limit (HTTP 429)", retryAfter);
    }

    /** {@code /fiction/12345/slug} (abs or relative) → {@code fiction/12345}. */
    private static String toSeriesPath(String href) {
        String[] segs = pathSegments(href);
        if (segs.length >= 2 && "fiction".equals(segs[0])) {
            return "fiction/" + segs[1];
        }
        return null;
    }

    /** {@code /fiction/12345/slug/chapter/67890/chslug} → {@code fiction/12345/chapter/67890}. */
    private static String toChapterPath(String href) {
        String[] segs = pathSegments(href);
        // segs: fiction, <id>, <slug>, chapter, <chapterId>, ...
        if (segs.length >= 5 && "fiction".equals(segs[0]) && "chapter".equals(segs[3])) {
            return "fiction/" + segs[1] + "/chapter/" + segs[4];
        }
        return null;
    }

    private static String[] pathSegments(String href) {
        if (href == null || href.isBlank()) {
            return new String[0];
        }
        String path;
        try {
            path = href.startsWith("http") ? URI.create(href).getRawPath() : href;
        } catch (Exception e) {
            path = href;
        }
        return path.replaceAll("^/+", "").split("/");
    }

    private static String strip(String externalId) {
        return externalId == null ? "" : externalId.replaceAll("^/+", "");
    }

    private static String absUrl(String src) {
        if (src == null || src.isBlank()) {
            return null;
        }
        if (src.startsWith("http")) {
            return src;
        }
        return BASE_URL + (src.startsWith("/") ? src : "/" + src);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static LocalDate parseDate(String iso) {
        try {
            return OffsetDateTime.parse(iso).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }
}
