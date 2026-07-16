package dev.kodex.plugin.mangakuro;

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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.pf4j.Extension;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class MangaKuroSource implements ContentSource {

    private static final String BASE_URL = "https://mangakuro.net";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern CHAPTER_ID = Pattern.compile("CHAPTER_ID\\s*=\\s*(\\d+);");
    private static final Pattern IMAGE = Pattern.compile("src=\\\\\"([^\"]+)\\\\\"");

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
        return "MangaKuro";
    }

    @Override
    public String website() {
        return BASE_URL;
    }

    @Override
    public String language() {
        return "ja";
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return parseList(getHtml(listUrl("all-manga", page, "sort", "views")));
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return parseList(getHtml(listUrl("all-manga", page, "sort", "latest-updated")));
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        return parseList(getHtml(listUrl("search", page, "keyword", query == null ? "" : query.trim())));
    }

    private static String listUrl(String type, int page, String key, String value) {
        return HttpUrl.get(BASE_URL).newBuilder()
            .addPathSegment(type)
            .addPathSegment(String.valueOf(Math.max(1, page)))
            .addQueryParameter(key, value)
            .build().toString();
    }

    private SeriesPage parseList(Document doc) {
        if (doc == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        for (Element element : doc.select(".story_item")) {
            Element a = element.selectFirst("a");
            if (a == null) {
                continue;
            }
            Element name = element.selectFirst(".mg_name a");
            Element img = element.selectFirst("img");
            String thumbnail = img != null ? img.attr("abs:src") : null;
            items.add(new SearchResult(id(), relative(a.attr("abs:href")),
                name != null ? name.text() : a.text(), null,
                thumbnail == null || thumbnail.isBlank() ? null : thumbnail,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        boolean hasNextPage = doc.selectFirst("a[title='Last Page']") != null;
        return new SeriesPage(items, hasNextPage);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId);
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        Element authorEl = doc.selectFirst("div:has(.lnr-user) + .info_value");
        Element statusEl = doc.selectFirst("div:has(.lnr-leaf) + .info_value");
        Element descEl = doc.selectFirst(".detail_reviewContent");
        Element cover = doc.selectFirst(".detail_avatar img");
        Element titleEl = doc.selectFirst(".detail_listInfo .title, h1");

        SeriesStatus status = statusEl != null && statusEl.text().contains("進行中")
            ? SeriesStatus.ONGOING : SeriesStatus.UNKNOWN;
        String thumbnail = cover != null ? cover.attr("abs:src") : null;

        return new SearchResult(id(), seriesExternalId,
            titleEl != null ? titleEl.text() : seriesExternalId,
            descEl != null ? descEl.text() : null,
            thumbnail == null || thumbnail.isBlank() ? null : thumbnail,
            authorEl != null ? authorEl.text() : null, null,
            List.of(), status, Map.of());
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId);
        List<SourceChapter> chapters = new ArrayList<>();
        if (doc == null) {
            return chapters;
        }
        for (Element element : doc.select(".chapter_box .item")) {
            Element a = element.selectFirst("a");
            if (a == null) {
                continue;
            }
            String text = a.text();
            String name = text.contains("# ") ? text.substring(text.indexOf("# ") + 2) : text;
            chapters.add(new SourceChapter(relative(a.attr("abs:href")), name,
                parseNumber(name), null, null, Map.of()));
        }
        return chapters;
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        List<SourcePage> pages = new ArrayList<>();
        String html = getString(BASE_URL + chapterExternalId);
        if (html == null) {
            return pages;
        }
        Matcher idMatcher = CHAPTER_ID.matcher(html);
        if (!idMatcher.find()) {
            return pages;
        }
        String apiBody = getString(BASE_URL + "/ajax/image/list/chap/" + idMatcher.group(1));
        if (apiBody == null) {
            return pages;
        }
        Map<String, String> headers = Map.of("Referer", BASE_URL + "/", "User-Agent", USER_AGENT);
        Matcher m = IMAGE.matcher(apiBody);
        int index = 0;
        while (m.find()) {
            pages.add(new SourcePage(index++, m.group(1), headers));
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private Document getHtml(String url) {
        String body = getString(url);
        return body == null ? null : Jsoup.parse(body, url);
    }

    private String getString(String url) {
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
            return body.string();
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
}
