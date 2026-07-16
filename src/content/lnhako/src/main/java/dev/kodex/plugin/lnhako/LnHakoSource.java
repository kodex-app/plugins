package dev.kodex.plugin.lnhako;

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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class LnHakoSource implements ContentSource {

    private static final String BASE_URL = "https://ln.hako.vn";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Pattern CSS_URL = Pattern.compile("url\\((['\"]?)(.*?)\\1\\)");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT);

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
        return "Hako";
    }

    @Override
    public String website() {
        return BASE_URL;
    }

    @Override
    public String language() {
        return "vi";
    }

    // Hako hotlink-protects cover images (Referer check) — send the site as Referer when the core fetches them.
    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("Referer", BASE_URL + "/");
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return advancedSearch(null, page, Filters.defaultFilterList("top"));
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return advancedSearch(null, page, Filters.defaultFilterList("capnhat"));
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        // Filtered browsing (genre/status/type/sort) arrives here too — with or without a keyword.
        FilterList effective = filters != null && !filters.filters().isEmpty() ? filters : Filters.defaultFilterList("");
        return advancedSearch(query, page, effective);
    }

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList("");
    }

    /**
     * Browse/search via Hako's advanced-search endpoint ({@code /tim-kiem-nang-cao}): the keyword becomes
     * {@code title}, and {@link Filters} contributes genre include/exclude, status, series type, author,
     * illustrator and sort. Popular/latest just preset the sort.
     */
    private SeriesPage advancedSearch(String title, int page, FilterList filters) {
        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/tim-kiem-nang-cao").newBuilder();
        if (title != null && !title.isBlank()) {
            url.addQueryParameter("title", title.trim());
        }
        Filters.applyToUrl(url, filters);
        url.addQueryParameter("page", String.valueOf(Math.max(1, page)));
        return parseNovels(getHtml(url.build().toString()), Math.max(1, page));
    }

    private SeriesPage parseNovels(Document doc, int page) {
        if (doc == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        for (Element item : doc.select(".thumb-item-flow")) {
            Element a = item.selectFirst(".series-title a");
            if (a == null) {
                a = item.selectFirst("a[href*=/truyen/]");
            }
            if (a == null) {
                continue;
            }
            String path = toPath(a.attr("href"));
            if (path == null) {
                continue;
            }
            String name = a.hasAttr("title") ? a.attr("title").trim() : a.text().trim();
            String cover = coverFrom(item.selectFirst(".img-in-ratio"));
            items.add(new SearchResult(id(), path, name, null, cover,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        boolean hasNext = doc.select("a[href*=page=]").stream()
            .anyMatch(a -> a.attr("href").contains("page=" + (page + 1)));
        return new SeriesPage(items, hasNext);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + path(seriesExternalId));
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        Element nameEl = doc.selectFirst(".series-name");
        String title = nameEl != null ? nameEl.text().trim() : seriesExternalId;
        String cover = coverFrom(doc.selectFirst(".series-cover .img-in-ratio"));
        if (cover == null) {
            cover = coverFrom(doc.selectFirst(".img-in-ratio"));
        }
        Element summaryEl = doc.selectFirst(".summary-content");
        String description = summaryEl != null ? summaryEl.wholeText().trim() : null;
        List<String> genres = doc.select(".series-gerne-item, .series-genre-item").eachText()
            .stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();

        String author = null;
        String status = "";
        for (Element info : doc.select(".info-item")) {
            Element nameSpan = info.selectFirst(".info-name");
            Element valueSpan = info.selectFirst(".info-value");
            String label = nameSpan != null ? nameSpan.text().trim().toLowerCase(Locale.ROOT) : "";
            String value = valueSpan != null ? valueSpan.text().trim() : "";
            if (label.contains("tác giả")) {
                author = value;
            } else if (label.contains("tình trạng")) {
                status = value;
            }
        }
        return new SearchResult(id(), seriesExternalId, title,
            description == null || description.isBlank() ? null : description, cover,
            author == null || author.isBlank() ? null : author, null, genres, parseStatus(status), Map.of());
    }

    private static SeriesStatus parseStatus(String status) {
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "đang tiến hành" -> SeriesStatus.ONGOING;
            case "tạm ngưng" -> SeriesStatus.ON_HIATUS;
            case "đã hoàn thành", "hoàn thành", "completed" -> SeriesStatus.COMPLETED;
            default -> SeriesStatus.UNKNOWN;
        };
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + path(seriesExternalId));
        List<SourceChapter> chapters = new ArrayList<>();
        if (doc == null) {
            return chapters;
        }
        int index = 0;
        // Hako groups chapters by volume; list them in document order (oldest → newest) with ascending numbers.
        for (Element volume : doc.select(".volume-list")) {
            Element volTitle = volume.selectFirst(".sect-title");
            String volumeName = volTitle != null ? volTitle.text().trim() : "";
            for (Element li : volume.select(".list-chapters li, ul li.chapter-item, ul li")) {
                Element a = li.selectFirst("a[href*=/truyen/], a[title][href]");
                if (a == null) {
                    continue;
                }
                String chapterPath = toPath(a.attr("href"));
                if (chapterPath == null) {
                    continue;
                }
                String name = a.hasAttr("title") ? a.attr("title").trim() : a.text().trim();
                LocalDate date = null;
                Element time = li.selectFirst(".chapter-time");
                if (time != null) {
                    date = parseDate(time.text().trim());
                }
                Map<String, String> attrs = volumeName.isEmpty() ? Map.of() : Map.of("volume", volumeName);
                chapters.add(new SourceChapter(chapterPath, name, (double) (++index), null, date, attrs));
            }
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
        Document doc = getHtml(BASE_URL + path(chapterExternalId));
        if (doc == null) {
            return new SourceChapterContent(null, "");
        }
        // Hako encrypts the chapter text into <div id="chapter-c-protected" data-c="[...]"> and decrypts it
        // client-side (see /scripts/app.js). Reproduce that here; unprotected chapters fall back to the
        // plain #chapter-content. (The upstream LNReader plugin scrapes raw HTML and doesn't decrypt, so it
        // no longer works on protected chapters either.)
        String contentHtml;
        Element protectedEl = doc.selectFirst("#chapter-c-protected");
        if (protectedEl != null) {
            contentHtml = decodeProtected(protectedEl);
        } else {
            Element content = doc.selectFirst("#chapter-content");
            contentHtml = content != null ? content.html() : "";
        }
        // Clean: drop scripts/ads and the "other story" banner links (<a href=".../truyen/...">) Hako appends
        // at the end of chapters (removing an <a> also drops the banner <img> it wraps).
        Document frag = Jsoup.parseBodyFragment(contentHtml, BASE_URL);
        frag.select("script, style, .ads-holder, a[href*=\"/truyen/\"]").remove();
        Element titleEl = doc.selectFirst(".title-top h4, h4.title-item, .chapter-title");
        String title = titleEl != null ? titleEl.text().trim() : null;
        return new SourceChapterContent(title, frag.body().html());
    }

    // ---- Hako chapter-text decryption (mirrors /scripts/app.js) -----------------------------------

    private static final Pattern JSON_STR = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    /** Decodes {@code #chapter-c-protected}: parse {@code data-c} (JSON array), order by the 4-char prefix, decode each part. */
    private static String decodeProtected(Element el) {
        String scheme = el.hasAttr("data-s") ? el.attr("data-s") : "none";
        String key = el.attr("data-k");
        List<String> parts = parseJsonStringArray(el.attr("data-c"));
        // Each element is prefixed with a 4-digit order index; sort then strip it before decoding.
        parts.sort(Comparator.comparingInt(s -> {
            try {
                return Integer.parseInt(s.substring(0, Math.min(4, s.length())));
            } catch (NumberFormatException e) {
                return 0;
            }
        }));
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(decodePart(scheme, p.length() > 4 ? p.substring(4) : "", key));
        }
        return sb.toString();
    }

    private static String decodePart(String scheme, String payload, String key) {
        try {
            if ("xor_shuffle".equals(scheme)) {
                byte[] data = Base64.getMimeDecoder().decode(payload);
                byte[] out = new byte[data.length];
                int klen = key.isEmpty() ? 1 : key.length();
                for (int i = 0; i < data.length; i++) {
                    int kc = key.isEmpty() ? 0 : key.charAt(i % klen);
                    out[i] = (byte) (data[i] ^ kc);
                }
                return new String(out, StandardCharsets.UTF_8);
            }
            String pl = "base64_reverse".equals(scheme) ? new StringBuilder(payload).reverse().toString() : payload;
            return new String(Base64.getMimeDecoder().decode(pl), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Minimal JSON-string-array parser (the payload is an array of base64-ish strings). */
    private static List<String> parseJsonStringArray(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        Matcher m = JSON_STR.matcher(json);
        while (m.find()) {
            out.add(unescapeJson(m.group(1)));
        }
        return out;
    }

    private static String unescapeJson(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '/' -> b.append('/');
                    case '\\' -> b.append('\\');
                    case '"' -> b.append('"');
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    // ---- HTTP / helpers --------------------------------------------------------------------------

    private Document getHtml(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            throwIfRateLimited(res);
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                return null;
            }
            return Jsoup.parse(body.string(), url);
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
        throw new ProviderRateLimitException("Hako rate limit (HTTP 429)", retryAfter);
    }

    /** A cover url from an {@code .img-in-ratio} element ({@code data-bg} attr, else CSS {@code background url(...)}). */
    private static String coverFrom(Element el) {
        if (el == null) {
            return null;
        }
        String bg = el.attr("data-bg");
        if (!bg.isBlank()) {
            return absUrl(bg);
        }
        Matcher m = CSS_URL.matcher(el.attr("style"));
        return m.find() ? absUrl(m.group(2)) : null;
    }

    /** Strips scheme+host from an absolute url, keeping the path (+ query); keeps a relative path as-is. */
    private static String toPath(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        try {
            if (href.startsWith("http")) {
                URI u = URI.create(href);
                String p = u.getRawPath() == null ? "" : u.getRawPath();
                return u.getRawQuery() == null ? p : p + "?" + u.getRawQuery();
            }
        } catch (Exception e) {
            return href;
        }
        return href.startsWith("/") ? href : "/" + href;
    }

    private static String path(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return "/";
        }
        return externalId.startsWith("/") ? externalId : "/" + externalId;
    }

    private static String absUrl(String src) {
        if (src == null || src.isBlank()) {
            return null;
        }
        if (src.startsWith("http")) {
            return src;
        }
        if (src.startsWith("//")) {
            return "https:" + src;
        }
        return BASE_URL + (src.startsWith("/") ? src : "/" + src);
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text, DMY);
        } catch (Exception e) {
            return null;
        }
    }
}
