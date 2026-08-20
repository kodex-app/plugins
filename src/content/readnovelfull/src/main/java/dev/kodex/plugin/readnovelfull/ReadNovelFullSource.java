package dev.kodex.plugin.readnovelfull;

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
import org.pf4j.Extension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ReadNovelFull family of web-novel sites — sites built on the same theme, differing only in which
 * paths they browse and how they serve a novel's chapter list. This plugin ships the two the project
 * asked for: {@link FreeWebNovel} and {@link NovelBin}.
 *
 * <p>Ported from the LNReader {@code readnovelfull} multisrc template. Upstream drives everything through
 * one streaming HTML parser; the same markup is expressed here as jsoup selectors, which is both shorter
 * and more tolerant of the layout drift between the two skins.
 */
public abstract class ReadNovelFullSource implements ContentSource {

    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(ReadNovelFullSource.class.getName());

    /** The chapter count the paginated listing endpoint reports, embedded in an inline script. */
    private static final Pattern TOTAL_CHAPTERS = Pattern.compile("totalChapters:\\s*(\\d+)");
    /** An alternative novel id, carried in a script as a query fragment. */
    private static final Pattern SOURCE_ID = Pattern.compile("sourceid=(\\d+)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    /** Zero-width non-joiners the sites use to break up scraped prose. */
    private static final String ZERO_WIDTH_NON_JOINER = "‌";

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    private final Config config;

    /**
     * Everything that differs between the sites in this family.
     *
     * @param baseUrl         site root, without a trailing slash
     * @param name            display name, matching the upstream source name
     * @param latestPage      path browsed for the "latest" feed
     * @param defaultType     path browsed by default (the site's most-popular listing)
     * @param typeOptions     the listings this site offers
     * @param genreOptions    the genres this site offers
     * @param pageAsPath      whether page 2+ is a path segment rather than a {@code ?page=} parameter
     * @param noAjax          whether the novel page already carries its full chapter list
     * @param noPages         listings that are single-page and must not be paginated
     * @param chapterListing  path of the chapter-list endpoint
     * @param chapterParam    query/form parameter naming the novel on that endpoint
     */
    record Config(
        String baseUrl,
        String name,
        String latestPage,
        String defaultType,
        List<Filters.Option> typeOptions,
        List<Filters.Option> genreOptions,
        boolean pageAsPath,
        boolean noAjax,
        Set<String> noPages,
        String chapterListing,
        String chapterParam
    ) {
    }

    protected ReadNovelFullSource(Config config) {
        this.config = config;
    }

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    /** These sites sit behind Cloudflare, so go through the operator's solver when one is configured. */
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
        return config.name();
    }

    @Override
    public String website() {
        return config.baseUrl();
    }

    @Override
    public String language() {
        return "en";
    }

    /** Applies the site's own chapter-text cleanup, beyond the shared ad/marker stripping. */
    protected void cleanChapterBody(Element content) {
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList(config.typeOptions(), config.genreOptions(), config.defaultType());
    }

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return browse(page, getFilterList(), false);
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return browse(page, getFilterList(), true);
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            FilterList effective = (filters == null || filters.filters().isEmpty()) ? getFilterList() : filters;
            return browse(page, effective, false);
        }
        // A keyword goes to the site's search page, which ignores the browse filters.
        HttpUrl url = HttpUrl.get(config.baseUrl() + "/search").newBuilder()
            .addQueryParameter("keyword", q)
            .addQueryParameter("page", String.valueOf(Math.max(1, page)))
            .build();
        Document doc = getHtml(url.toString());
        return doc == null ? SeriesPage.empty() : parseNovels(doc);
    }

    /**
     * Browses one of the site's listing paths. A chosen genre replaces the listing entirely, and the
     * "latest" feed overrides both — the same precedence the upstream plugin applies.
     */
    private SeriesPage browse(int page, FilterList filters, boolean latest) {
        String type = config.defaultType();
        String genre = null;
        for (Filter<?> filter : filters.filters()) {
            if (!(filter instanceof Filter.Select select)) {
                continue;
            }
            if (Filters.TYPE.equals(select.name())) {
                String value = Filters.selectedValue(select, config.typeOptions());
                if (value != null) {
                    type = value;
                }
            } else if (Filters.GENRE.equals(select.name())) {
                genre = Filters.selectedValue(select, Filters.withNone(config.genreOptions()));
            }
        }

        String basePage = latest ? config.latestPage() : genre != null ? genre : type;
        int index = Math.max(1, page);
        // Some listings render everything at once and 404 (or loop) on a second page.
        if (index > 1 && !latest && genre == null && config.noPages().contains(type)) {
            return SeriesPage.empty();
        }

        String url;
        if (config.pageAsPath()) {
            url = index > 1
                ? config.baseUrl() + "/" + basePage + "/" + index
                : config.baseUrl() + "/" + basePage;
        } else {
            url = config.baseUrl() + "/" + basePage + "?page=" + index;
        }

        Document doc = getHtml(url);
        return doc == null ? SeriesPage.empty() : parseNovels(doc);
    }

    /** Result cards live in the listing column; each pairs a cover image with an {@code h3} title link. */
    private SeriesPage parseNovels(Document doc) {
        List<SearchResult> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select("[class*=archive] h3 a[href], .col-content h3 a[href]")) {
            String path = pathOf(link.absUrl("href"));
            if (path == null || !seen.add(path)) {
                continue;
            }
            String name = link.hasAttr("title") ? link.attr("title").trim() : link.text().trim();
            if (name.isEmpty()) {
                continue;
            }
            items.add(new SearchResult(id(), path, name, null, cardCover(link),
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        return new SeriesPage(items, !items.isEmpty());
    }

    /** Walks up from a title link to the card that holds it, and takes that card's cover image. */
    private static String cardCover(Element link) {
        Element node = link.parent();
        for (int depth = 0; node != null && depth < 4; depth++, node = node.parent()) {
            Element img = node.selectFirst("img");
            if (img != null) {
                return imageUrl(img);
            }
        }
        return null;
    }

    /** Covers are lazy-loaded, so the real URL may sit on a data attribute rather than {@code src}. */
    private static String imageUrl(Element img) {
        for (String attr : new String[] {"data-src", "data-cfsrc", "src"}) {
            if (img.hasAttr(attr)) {
                String url = img.absUrl(attr);
                if (!url.isBlank()) {
                    return url;
                }
            }
        }
        return null;
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(config.baseUrl() + "/" + seriesExternalId);
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }

        Element cover = doc.selectFirst("div.books img, div.m-imgtxt img");
        String title = null;
        if (cover != null && cover.hasAttr("title")) {
            title = emptyToNull(cover.attr("title").trim());
        }
        if (title == null) {
            Element heading = doc.selectFirst("div.books h3, div.m-imgtxt h3, h3.title");
            title = heading != null ? heading.text().trim() : seriesExternalId;
        }

        String author = null;
        String status = null;
        List<String> genres = new ArrayList<>();

        // The common skin lists the metadata as "Key: value" rows.
        for (Element row : doc.select("ul.info-meta li, div.info li")) {
            String line = row.text().trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = normalizeList(line.substring(colon + 1));
            switch (key) {
                case "author" -> author = emptyToNull(value);
                case "genre" -> genres.addAll(splitList(value));
                case "status" -> status = value;
                default -> {
                }
            }
        }
        // The other skin labels each field with a titled span instead.
        if (author == null) {
            author = emptyToNull(labelledValue(doc, "Author"));
        }
        if (genres.isEmpty()) {
            genres.addAll(splitList(labelledValue(doc, "Genre")));
        }
        if (status == null) {
            status = labelledValue(doc, "Status");
        }

        Element summary = doc.selectFirst("div.desc-text, div.inner");
        String description = summary == null ? null : emptyToNull(summary.wholeText().trim());

        return new SearchResult(id(), seriesExternalId, title, description,
            cover == null ? null : imageUrl(cover),
            author, null, genres, parseStatus(status), Map.of());
    }

    /** Reads a field written as {@code <span title="Author">…</span>} followed by its value. */
    private static String labelledValue(Document doc, String label) {
        Element span = doc.selectFirst("span[title=" + label + "]");
        if (span == null || span.parent() == null) {
            return null;
        }
        Element holder = span.parent();
        List<String> values = new ArrayList<>();
        for (Element link : holder.select("a")) {
            String text = link.text().trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        if (!values.isEmpty()) {
            return String.join(", ", values);
        }
        // No links — take the container's own text minus the label itself.
        String text = holder.text().trim();
        String spanText = span.text().trim();
        return normalizeList(text.startsWith(spanText) ? text.substring(spanText.length()) : text);
    }

    private static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String normalizeList(String value) {
        return String.join(", ", splitList(value));
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
        Document doc = getHtml(config.baseUrl() + "/" + seriesExternalId);
        if (doc == null) {
            return List.of();
        }

        Integer totalChapters = null;
        String novelId = null;

        Element indexList = doc.selectFirst("#indexListPage[data-novel-id]");
        if (indexList != null) {
            novelId = emptyToNull(indexList.attr("data-novel-id"));
            totalChapters = parseIntOrNull(indexList.attr("data-total-chapters"));
        }
        if (novelId == null && !config.noAjax()) {
            Element rating = doc.selectFirst("#rating[data-novel-id]");
            if (rating != null) {
                novelId = emptyToNull(rating.attr("data-novel-id"));
            }
        }
        if (novelId == null) {
            Element setCase = doc.selectFirst("a.set-case[data-articleid]");
            if (setCase != null) {
                novelId = emptyToNull(setCase.attr("data-articleid"));
            }
        }
        for (Element script : doc.select("script")) {
            String body = script.data();
            if (totalChapters == null && body.contains("window.chapterPagination")) {
                Matcher m = TOTAL_CHAPTERS.matcher(body);
                if (m.find()) {
                    totalChapters = parseIntOrNull(m.group(1));
                }
            }
            if (novelId == null && body.contains("sourceid")) {
                Matcher m = SOURCE_ID.matcher(body);
                if (m.find()) {
                    novelId = m.group(1);
                }
            }
        }

        // Sites that render the whole list inline need no second request.
        if (config.noAjax() && totalChapters == null) {
            List<SourceChapter> inline = inlineChapters(doc, seriesExternalId);
            if (!inline.isEmpty()) {
                return inline;
            }
        }

        if (novelId == null) {
            Matcher m = DIGITS.matcher(seriesExternalId);
            novelId = m.find() ? m.group() : null;
        }
        if (novelId == null) {
            LOG.log(System.Logger.Level.WARNING,
                () -> config.name() + ": could not determine a novel id for " + seriesExternalId);
            return List.of();
        }

        Element disqus = doc.selectFirst("span[data-disqus-identifier]");
        String novelTitle = disqus != null ? emptyToNull(disqus.attr("data-disqus-identifier")) : null;
        return ajaxChapters(seriesExternalId, novelId, novelTitle, totalChapters);
    }

    /** The chapter list already on the novel page, as a {@code <ul id="idData">} of links. */
    private List<SourceChapter> inlineChapters(Document doc, String novelPath) {
        List<SourceChapter> chapters = new ArrayList<>();
        int index = 0;
        for (Element link : doc.select("ul#idData a")) {
            index++;
            String href = link.attr("href");
            String path = href.isBlank()
                ? novelPath.replace(".html", "/chapter-" + index + ".html")
                : stripLeadingSlash(href);
            String name = link.hasAttr("title") ? link.attr("title").trim() : link.text().trim();
            if (name.isEmpty()) {
                name = "Chapter " + index;
            }
            chapters.add(new SourceChapter(path, name, (double) index, null, null, Map.of()));
        }
        return chapters;
    }

    /**
     * Fetches the chapter list from the site's listing endpoint. Sites that report a chapter count expect
     * a POST carrying a random chapter cursor; the rest answer a plain GET.
     */
    private List<SourceChapter> ajaxChapters(String novelPath, String novelId, String novelTitle,
                                             Integer totalChapters) {
        String payload;
        if (totalChapters != null && totalChapters > 0) {
            String acode = novelTitle != null ? novelTitle : lastSegment(novelPath);
            RequestBody form = new FormBody.Builder()
                .add(config.chapterParam(), novelId)
                .add("acode", acode)
                .add("cid", String.valueOf(ThreadLocalRandom.current().nextInt(totalChapters)))
                .build();
            payload = post(config.baseUrl() + "/" + config.chapterListing(), form);
        } else {
            HttpUrl url = HttpUrl.get(config.baseUrl() + "/" + config.chapterListing()).newBuilder()
                .addQueryParameter(config.chapterParam(), novelId)
                .build();
            payload = getText(url.toString(), "text/html,application/xhtml+xml,*/*");
        }
        if (payload == null) {
            return List.of();
        }

        // Some sites wrap the listing markup in a JSON envelope.
        String html = payload;
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode inner = root.get("html");
            if (inner != null && inner.isValueNode()) {
                html = inner.asString();
            }
        } catch (Exception ignored) {
            // Not JSON — the response is the markup itself.
        }

        Document doc = Jsoup.parse(html, config.baseUrl());
        List<SourceChapter> chapters = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        for (Element element : doc.select("a[href], option[value]")) {
            String href = "a".equals(element.tagName()) ? element.attr("href") : element.attr("value");
            if (href.isBlank()) {
                continue;
            }
            String path = toChapterPath(href);
            if (!seen.add(path)) {
                continue;
            }
            String name = element.hasAttr("title") ? element.attr("title").trim() : element.text().trim();
            if (name.isEmpty()) {
                continue;
            }
            index++;
            chapters.add(new SourceChapter(path, name, (double) index, null, null, Map.of()));
        }
        return chapters;
    }

    private String toChapterPath(String href) {
        if (href.startsWith("/")) {
            return href.substring(1);
        }
        String prefix = config.baseUrl() + "/";
        return href.startsWith(prefix) ? href.substring(prefix.length()) : href;
    }

    // ---- Content ---------------------------------------------------------------------------------

    /** BOOK sources serve text, not page images. */
    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        return List.of();
    }

    @Override
    public SourceChapterContent chapterContent(String chapterExternalId, ProviderSettings settings) {
        Document doc = getHtml(config.baseUrl() + "/" + chapterExternalId);
        if (doc == null) {
            return new SourceChapterContent(null, "");
        }
        Element content = doc.selectFirst("#chr-content, #chapter-content, .txt");
        if (content == null) {
            LOG.log(System.Logger.Level.WARNING, () ->
                config.name() + ": no chapter body at " + config.baseUrl() + "/" + chapterExternalId);
            return new SourceChapterContent(null, "");
        }

        // Shared chrome: hidden decoys, embeds and ad slots the theme injects into the prose.
        content.select("sub, iframe, script, style, ins, [class*=unlock-buttons], [class*=ads], .adsbygoogle")
            .remove();
        cleanChapterBody(content);

        Element heading = doc.selectFirst(".chr-title, .chapter-title, a.chr-title");
        String title = heading == null ? null : emptyToNull(heading.text().trim());
        String html = content.html().replace(ZERO_WIDTH_NON_JOINER, "").replace("&nbsp;", " ");
        return new SourceChapterContent(title, html);
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private Document getHtml(String url) {
        String payload = getText(url, "text/html,application/xhtml+xml,*/*");
        return payload == null ? null : Jsoup.parse(payload, url);
    }

    private String getText(String url, String accept) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", config.baseUrl() + "/")
            .header("Accept", accept)
            .get().build();
        return execute(req, url);
    }

    private String post(String url, RequestBody form) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", config.baseUrl() + "/")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(form).build();
        return execute(req, url);
    }

    private String execute(Request req, String url) {
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            if (!res.isSuccessful() || payload == null) {
                int code = res.code();
                LOG.log(System.Logger.Level.WARNING, () -> config.name() + " HTTP " + code + " for " + url);
                return null;
            }
            return payload;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> config.name() + " request failed for " + url, e);
            return null; // fail soft, per the SPI contract
        }
    }

    /** Novels and chapters are keyed by site path without the leading slash, matching upstream. */
    private static String pathOf(String absoluteUrl) {
        if (absoluteUrl == null || absoluteUrl.isBlank()) {
            return null;
        }
        try {
            String path = URI.create(absoluteUrl).getRawPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return null;
            }
            return stripLeadingSlash(path);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ---- The sites in this family ----------------------------------------------------------------

    /**
     * freewebnovel.com. Paginates by path segment, ships its chapter list inline, and salts chapter prose
     * with a homoglyph watermark that {@link Watermark} removes.
     */
    @Extension
    public static final class FreeWebNovel extends ReadNovelFullSource {

        public FreeWebNovel() {
            super(new Config(
                "https://freewebnovel.com",
                "Free Web Novel",
                "sort/latest-release",
                "sort/most-popular",
                Filters.FWN_TYPE_OPTIONS,
                Filters.FWN_GENRE_OPTIONS,
                true,
                true,
                Set.of("sort/most-popular"),
                "api/chapterlist.php",
                "aid"));
        }

        @Override
        protected void cleanChapterBody(Element content) {
            Watermark.strip(content);
        }
    }

    /** novelbin.com. Paginates by query parameter and serves its chapter list from an AJAX endpoint. */
    @Extension
    public static final class NovelBin extends ReadNovelFullSource {

        public NovelBin() {
            super(new Config(
                "https://novelbin.com",
                "Novel Bin",
                "sort/latest",
                "sort/top-view-novel",
                Filters.NOVELBIN_TYPE_OPTIONS,
                Filters.NOVELBIN_GENRE_OPTIONS,
                false,
                false,
                Set.of(),
                "ajax/chapter-archive",
                "novelId"));
        }

        @Override
        protected void cleanChapterBody(Element content) {
            // The theme injects an app advert into the chapter body.
            content.select("[class*=app-promo]").remove();
        }
    }
}
