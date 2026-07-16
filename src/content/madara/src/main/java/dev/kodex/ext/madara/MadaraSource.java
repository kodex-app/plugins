package dev.kodex.ext.madara;

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
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class MadaraSource implements ContentSource {

    protected static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";
    private static final String ACCEPT_LANGUAGE = "en-US,en;q=0.9";
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern REL_NUMBER = Pattern.compile("(\\d+)");

    private static final Set<String> COMPLETED = lower(
        "Completed", "Completo", "Completado", "Concluído", "Concluido", "Finalizado", "Hoàn Thành", "已完结");
    private static final Set<String> ONGOING = lower(
        "OnGoing", "Ongoing", "Updating", "Em Lançamento", "Em andamento", "En cours", "Ativo",
        "Lançando", "Đang Tiến Hành", "连载中", "Publicandose", "En curso");
    private static final Set<String> HIATUS = lower("On Hold", "Pausado", "En espera", "Durduruldu");
    private static final Set<String> CANCELED = lower("Canceled", "Cancelled", "Cancelado", "Đã hủy", "Annulé");

    protected final String name;
    protected final String baseUrl;
    protected final String lang;
    protected final String mangaSubString;
    protected final boolean filterNonMangaItems;

    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    protected MadaraSource(String name, String baseUrl, String lang, boolean filterNonMangaItems) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.lang = lang;
        this.mangaSubString = "manga";
        this.filterNonMangaItems = filterNonMangaItems;
    }

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
        return name;
    }

    @Override
    public boolean adultContent() {
        return true;
    }

    @Override
    public String website() {
        return baseUrl;
    }

    @Override
    public String language() {
        return lang;
    }

    // ---- Browse / search -------------------------------------------------------------------------

    private String mangaEntrySuffix() {
        return filterNonMangaItems ? ".manga" : "";
    }

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        String url = baseUrl + "/" + mangaSubString + "/" + searchPage(page) + "?m_orderby=views";
        return parseList(getHtml(url), popularSelector());
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        String url = baseUrl + "/" + mangaSubString + "/" + searchPage(page) + "?m_orderby=latest";
        return parseList(getHtml(url), popularSelector());
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        HttpUrl.Builder url = HttpUrl.get(baseUrl + "/" + searchPage(page)).newBuilder();
        url.addQueryParameter("s", query == null ? "" : query.trim());
        url.addQueryParameter("post_type", "wp-manga");
        applyFilters(url, filters);
        return parseList(getHtml(url.build().toString()), "div.c-tabs-item__content , .manga__item");
    }

    private String popularSelector() {
        return "div.page-item-detail:not(:has(a[href*='bilibilicomics.com']))" + mangaEntrySuffix() + " , .manga__item";
    }

    private static String searchPage(int page) {
        return page <= 1 ? "" : "page/" + page + "/";
    }

    private SeriesPage parseList(Document doc, String selector) {
        if (doc == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element element : doc.select(selector)) {
            Element a = element.selectFirst("div.post-title a");
            if (a == null) {
                continue;
            }
            String externalId = relative(a.attr("abs:href"));
            if (!seen.add(externalId)) {
                continue;
            }
            Element img = element.selectFirst("img");
            String thumbnail = img != null ? imageFromElement(img) : null;
            items.add(new SearchResult(id(), externalId, a.ownText(), null,
                thumbnail, null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        boolean hasNextPage = doc.selectFirst("div.nav-previous, nav.navigation-ajax, a.nextpostslink") != null;
        return new SeriesPage(items, hasNextPage);
    }

    // ---- Filters ---------------------------------------------------------------------------------

    private static final String AUTHOR = "Author";
    private static final String ARTIST = "Artist";
    private static final String YEAR = "Year of Released";
    private static final String ORDER_BY = "Order by";
    private static final List<String> ORDER_BY_LABELS = List.of(
        "Relevance", "Latest", "A-Z", "Rating", "Trending", "Most Views", "New");
    private static final List<String> ORDER_BY_VALUES = List.of(
        "", "latest", "alphabet", "rating", "trending", "views", "new-manga");

    @Override
    public FilterList getFilterList() {
        return new FilterList(List.of(
            new Filter.TextFilter(AUTHOR),
            new Filter.TextFilter(ARTIST),
            new Filter.TextFilter(YEAR),
            new Filter.Select(ORDER_BY, ORDER_BY_LABELS, 0)));
    }

    private void applyFilters(HttpUrl.Builder url, FilterList filters) {
        if (filters == null) {
            return;
        }
        for (Filter<?> f : filters.filters()) {
            switch (f) {
                case Filter.TextFilter text -> {
                    String v = text.state();
                    if (v != null && !v.isBlank()) {
                        switch (text.name()) {
                            case AUTHOR -> url.addQueryParameter("author", v);
                            case ARTIST -> url.addQueryParameter("artist", v);
                            case YEAR -> url.addQueryParameter("release", v);
                            default -> { }
                        }
                    }
                }
                case Filter.Select select -> {
                    if (ORDER_BY.equals(select.name())) {
                        int idx = select.state() == null ? 0 : select.state();
                        if (idx > 0 && idx < ORDER_BY_VALUES.size()) {
                            url.addQueryParameter("m_orderby", ORDER_BY_VALUES.get(idx));
                        }
                    }
                }
                default -> { }
            }
        }
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(baseUrl + seriesExternalId);
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        Element titleEl = doc.selectFirst("div.post-title h3, div.post-title h1, #manga-title > h1");
        String title = titleEl != null ? titleEl.ownText() : seriesExternalId;

        String author = joinText(doc.select("div.author-content > a, div.manga-authors > a"));
        String artist = joinText(doc.select("div.artist-content > a"));

        String description = null;
        Element descEl = doc.selectFirst("div.description-summary div.summary__content, "
            + "div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt");
        if (descEl != null) {
            var paras = descEl.select("p");
            description = paras.isEmpty() ? descEl.text()
                : paras.stream().map(Element::text).reduce((a, b) -> a + "\n\n" + b).orElse("");
        }

        Element cover = doc.selectFirst("div.summary_image img");
        String thumbnail = cover != null ? imageFromElement(cover) : null;

        SeriesStatus status = SeriesStatus.UNKNOWN;
        var statusEls = doc.select("div.summary-content, div.summary-heading:contains(Status) + div");
        if (!statusEls.isEmpty()) {
            status = parseStatus(statusEls.last().text());
        }

        Set<String> genres = new LinkedHashSet<>();
        for (Element g : doc.select("div.genres-content a")) {
            genres.add(g.text());
        }
        Element type = doc.selectFirst(".post-content_item:contains(Type) .summary-content");
        if (type != null) {
            String t = type.ownText();
            if (!t.isBlank() && !t.equals("-") && !t.equalsIgnoreCase("Updating")) {
                genres.add(t);
            }
        }

        return new SearchResult(id(), seriesExternalId, title,
            description == null || description.isBlank() ? null : description,
            thumbnail, author, artist, new ArrayList<>(genres), status, Map.of());
    }

    private static SeriesStatus parseStatus(String raw) {
        if (raw == null) {
            return SeriesStatus.UNKNOWN;
        }
        String s = raw.toLowerCase(Locale.ROOT).trim();
        if (COMPLETED.contains(s)) {
            return SeriesStatus.COMPLETED;
        }
        if (ONGOING.contains(s)) {
            return SeriesStatus.ONGOING;
        }
        if (HIATUS.contains(s)) {
            return SeriesStatus.ON_HIATUS;
        }
        if (CANCELED.contains(s)) {
            return SeriesStatus.CANCELLED;
        }
        return SeriesStatus.UNKNOWN;
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(baseUrl + seriesExternalId);
        List<SourceChapter> chapters = new ArrayList<>();
        if (doc == null) {
            return chapters;
        }
        var elements = doc.select("li.wp-manga-chapter");
        if (elements.isEmpty()) {
            Element wrapper = doc.selectFirst("div[id^=manga-chapters-holder]");
            if (wrapper != null) {
                String mangaUrl = stripTrailingSlash(baseUrl + seriesExternalId);
                String mangaId = wrapper.attr("data-id");
                Document xhr = fetchChapterXhr(mangaUrl, mangaId);
                if (xhr != null) {
                    elements = xhr.select("li.wp-manga-chapter");
                }
            }
        }
        for (Element element : elements) {
            Element a = element.selectFirst("a");
            if (a == null) {
                continue;
            }
            String url = a.attr("abs:href");
            url = url.contains("?style=paged") ? url.substring(0, url.indexOf("?style=paged")) : url;
            if (!url.endsWith("?style=list")) {
                url = url + "?style=list";
            }
            String chapterName = a.text();
            chapters.add(new SourceChapter(relative(url), chapterName,
                parseNumber(chapterName), null, parseChapterDate(element), Map.of()));
        }
        return chapters;
    }

    private Document fetchChapterXhr(String mangaUrl, String mangaId) {
        // Old endpoint first, fall back to the newer /ajax/chapters on HTTP 400.
        FormBody form = new FormBody.Builder()
            .add("action", "manga_get_chapters")
            .add("manga", mangaId)
            .build();
        Request oldReq = xhrRequest(baseUrl + "/wp-admin/admin-ajax.php", form);
        try (Response res = http().newCall(oldReq).execute()) {
            if (res.code() != 400 && res.isSuccessful() && res.body() != null) {
                return Jsoup.parse(res.body().string(), mangaUrl);
            }
        } catch (Exception ignored) {
            // fall through to new endpoint
        }
        Request newReq = xhrRequest(mangaUrl + "/ajax/chapters", null);
        try (Response res = http().newCall(newReq).execute()) {
            if (res.isSuccessful() && res.body() != null) {
                return Jsoup.parse(res.body().string(), mangaUrl);
            }
        } catch (Exception ignored) {
            // give up
        }
        return null;
    }

    private Request xhrRequest(String url, RequestBody body) {
        Request.Builder b = browserHeaders(new Request.Builder().url(url))
            .header("X-Requested-With", "XMLHttpRequest");
        return (body == null ? b.post(RequestBody.create(new byte[0], null)) : b.post(body)).build();
    }

    /** Applies a realistic browser header set so the Cloudflare-fronted Madara sites don't 403 the request. */
    private Request.Builder browserHeaders(Request.Builder b) {
        return b
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("Upgrade-Insecure-Requests", "1")
            .header("Referer", baseUrl + "/");
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        Document doc = getHtml(baseUrl + chapterExternalId);
        List<SourcePage> pages = new ArrayList<>();
        if (doc == null) {
            return pages;
        }
        Map<String, String> headers = Map.of("Referer", baseUrl + "/", "User-Agent", USER_AGENT);
        var elements = doc.select("div.page-break, li.blocks-gallery-item, "
            + ".reading-content .text-left:not(:has(.blocks-gallery-item)) img");
        int index = 0;
        for (Element element : elements) {
            Element img = element.tagName().equals("img") ? element : element.selectFirst("img");
            if (img == null) {
                continue;
            }
            String src = imageFromElement(img);
            if (src != null && !src.isBlank()) {
                pages.add(new SourcePage(index++, src, headers));
            }
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", baseUrl + "/");
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    protected static String imageFromElement(Element element) {
        if (element.hasAttr("data-src")) {
            return element.attr("abs:data-src");
        }
        if (element.hasAttr("data-lazy-src")) {
            return element.attr("abs:data-lazy-src");
        }
        if (element.hasAttr("srcset")) {
            String best = bestFromSrcSet(element.attr("abs:srcset"));
            if (best != null) {
                return best;
            }
        }
        if (element.hasAttr("data-cfsrc")) {
            return element.attr("abs:data-cfsrc");
        }
        if (element.hasAttr("data-manga-src")) {
            return element.attr("abs:data-manga-src");
        }
        return element.attr("abs:src");
    }

    private static String bestFromSrcSet(String srcset) {
        String best = null;
        for (String token : srcset.split(" ")) {
            if (token.startsWith("http") && (best == null || token.compareTo(best) > 0)) {
                best = token;
            }
        }
        return best;
    }

    private static String joinText(Elements elements) {
        List<String> parts = new ArrayList<>();
        for (Element e : elements) {
            String t = e.text();
            if (!t.isBlank() && !t.matches("(?i).*\\b(updating|atualizando)\\b.*")) {
                parts.add(t);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private LocalDate parseChapterDate(Element element) {
        Element img = element.selectFirst("img:not(.thumb)");
        if (img != null) {
            LocalDate d = parseRelativeDate(img.attr("alt"));
            if (d != null) {
                return d;
            }
        }
        Element span = element.selectFirst("span.chapter-release-date");
        String text = span != null ? span.text() : null;
        if (text == null || text.isBlank()) {
            return null;
        }
        LocalDate rel = parseRelativeDate(text);
        if (rel != null) {
            return rel;
        }
        // Absolute date, e.g. "January 05, 2024".
        for (String pattern : new String[]{"MMMM dd, yyyy", "MMM dd, yyyy", "MMMM d, yyyy"}) {
            try {
                return LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern(pattern, Locale.US));
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private static LocalDate parseRelativeDate(String date) {
        if (date == null) {
            return null;
        }
        String d = date.toLowerCase(Locale.ROOT);
        if (d.startsWith("yesterday")) {
            return LocalDate.now().minusDays(1);
        }
        if (d.startsWith("today")) {
            return LocalDate.now();
        }
        Matcher m = REL_NUMBER.matcher(d);
        if (!m.find()) {
            return null;
        }
        int n = Integer.parseInt(m.group(1));
        if (d.contains("year") || d.contains("año") || d.contains("năm")) {
            return LocalDate.now().minusYears(n);
        }
        if (d.contains("month") || d.contains("mes") || d.contains("tháng")) {
            return LocalDate.now().minusMonths(n);
        }
        if (d.contains("week") || d.contains("semana") || d.contains("tuần")) {
            return LocalDate.now().minusWeeks(n);
        }
        if (d.contains("day") || d.contains("dia") || d.contains("día") || d.contains("ngày")) {
            return LocalDate.now().minusDays(n);
        }
        if (d.contains("hour") || d.contains("hora") || d.contains("min") || d.contains("second")) {
            return LocalDate.now();
        }
        return null;
    }

    private Document getHtml(String url) {
        Request req = browserHeaders(new Request.Builder().url(url)).get().build();
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

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private String relative(String absUrl) {
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

    private static Set<String> lower(String... values) {
        Set<String> set = new LinkedHashSet<>();
        for (String v : values) {
            set.add(v.toLowerCase(Locale.ROOT));
        }
        return set;
    }
}
