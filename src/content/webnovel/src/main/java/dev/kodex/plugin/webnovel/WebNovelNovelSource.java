package dev.kodex.plugin.webnovel;

import dev.kodex.spi.MediaKind;
import dev.kodex.spi.PluginConfigSchema;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebNovel (webnovel.com) — the site's <em>novel</em> catalogue, scraped from its HTML pages.
 *
 * <p>This is a separate source from {@link WebNovelComicSource}: the comics side is served by a JSON
 * API under {@code /go/pcm}, while novels are only rendered into the page, so the two share nothing
 * beyond the domain.
 *
 * <p>Ported from the LNReader {@code webnovel} plugin.
 */
@Extension
public class WebNovelNovelSource implements ContentSource {

    private static final String BASE_URL = "https://www.webnovel.com";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final System.Logger LOG = System.getLogger(WebNovelNovelSource.class.getName());

    /** Volume headings read like "Volume 3 — …"; only the number is kept. */
    private static final Pattern VOLUME = Pattern.compile("Volume\\s+(\\d+)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Hide chapters that need a paid unlock, mirroring the plugin's own switch. */
    static final String PREF_HIDE_LOCKED = "hide_locked";

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
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
    public MediaKind kind() {
        return MediaKind.BOOK;
    }

    /** Named apart from the comics source so the two are tellable apart in a source list. */
    @Override
    public String displayName() {
        return "Webnovel (Novels)";
    }

    @Override
    public String website() {
        return BASE_URL;
    }

    @Override
    public String language() {
        return "en";
    }

    @Override
    public PluginConfigSchema configSchema() {
        return new PluginConfigSchema(List.of(
            PluginConfigSchema.Field.bool(PREF_HIDE_LOCKED, "Hide locked chapters", false)));
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public FilterList getFilterList() {
        return NovelFilters.defaultFilterList(NovelFilters.SORT_POPULAR);
    }

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return browse(page, NovelFilters.defaultFilterList(NovelFilters.SORT_POPULAR));
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return browse(page, NovelFilters.defaultFilterList(NovelFilters.SORT_LATEST));
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            FilterList effective = (filters == null || filters.filters().isEmpty()) ? getFilterList() : filters;
            return browse(page, effective);
        }
        return keywordSearch(q, page, null);
    }

    /**
     * Builds a {@code /stories/…} listing URL. A chosen genre replaces the {@code novel} path segment
     * outright, and which genre list applies depends on the gender pick — so the filters can't simply be
     * appended as parameters.
     */
    private SeriesPage browse(int page, FilterList filters) {
        String sort = NovelFilters.SORT_POPULAR;
        String status = "0";
        String type = "0";
        String gender = NovelFilters.GENDER_MALE;
        String maleGenre = NovelFilters.GENDER_MALE;
        String femaleGenre = NovelFilters.GENDER_FEMALE;
        String fanfic = "";

        for (Filter<?> filter : filters.filters()) {
            if (filter instanceof Filter.TextFilter text && NovelFilters.FANFIC.equals(text.name())) {
                fanfic = text.state().trim();
            } else if (filter instanceof Filter.Select select) {
                String value = NovelFilters.selectedValue(select);
                if (value == null) {
                    continue;
                }
                switch (select.name()) {
                    case NovelFilters.SORT -> sort = value;
                    case NovelFilters.STATUS -> status = value;
                    case NovelFilters.TYPE -> type = value;
                    case NovelFilters.GENDER -> gender = value;
                    case NovelFilters.MALE_GENRES -> maleGenre = value;
                    case NovelFilters.FEMALE_GENRES -> femaleGenre = value;
                    default -> {
                    }
                }
            }
        }

        // The fanfic box is a search in disguise, and takes precedence over everything else.
        if (!fanfic.isEmpty()) {
            return keywordSearch(fanfic, page, "fanfic");
        }

        String segment = "novel";
        String genderParam = null;
        if (NovelFilters.GENDER_FEMALE.equals(gender)) {
            if (!NovelFilters.GENDER_FEMALE.equals(femaleGenre)) {
                segment = femaleGenre; // e.g. novel-fantasy-female
            } else {
                genderParam = NovelFilters.GENDER_FEMALE;
            }
        } else {
            if (!NovelFilters.GENDER_MALE.equals(maleGenre)) {
                segment = maleGenre; // e.g. novel-action-male
            } else {
                genderParam = NovelFilters.GENDER_MALE;
            }
        }

        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/stories/" + segment).newBuilder();
        if (genderParam != null) {
            url.addQueryParameter("gender", genderParam);
        }
        if (NovelFilters.TYPE_MTL.equals(type)) {
            // "MTL" isn't its own sourceType — it's translated content flagged as machine-translated.
            url.addQueryParameter("translateMode", "3");
            url.addQueryParameter("sourceType", "1");
        } else {
            url.addQueryParameter("sourceType", type);
        }
        url.addQueryParameter("bookStatus", status);
        url.addQueryParameter("orderBy", sort);
        url.addQueryParameter("pageIndex", String.valueOf(Math.max(1, page)));

        Document doc = getHtml(url.build().toString());
        return doc == null ? SeriesPage.empty() : parseNovels(doc, true);
    }

    private SeriesPage keywordSearch(String query, int page, String type) {
        // Upstream collapses whitespace to "+" before encoding, so keep that shape.
        String keywords = WHITESPACE.matcher(query).replaceAll("+");
        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/search").newBuilder()
            .addQueryParameter("keywords", keywords)
            .addQueryParameter("pageIndex", String.valueOf(Math.max(1, page)));
        if (type != null) {
            url.addQueryParameter("type", type);
        }
        Document doc = getHtml(url.build().toString());
        return doc == null ? SeriesPage.empty() : parseNovels(doc, false);
    }

    /**
     * Listing cards. The two listings differ in both their container and where the cover URL lives: the
     * category pages lazy-load covers from {@code data-original}, while search renders {@code src}.
     */
    private SeriesPage parseNovels(Document doc, boolean category) {
        String container = category ? ".j_category_wrapper" : ".j_list_container";
        String coverAttr = category ? "data-original" : "src";

        List<SearchResult> items = new ArrayList<>();
        for (Element card : doc.select(container + " li")) {
            Element thumb = card.selectFirst(".g_thumb");
            if (thumb == null) {
                continue;
            }
            String path = thumb.attr("href");
            if (path.isBlank()) {
                continue;
            }
            String title = thumb.hasAttr("title") ? thumb.attr("title").trim() : "";
            if (title.isEmpty()) {
                continue;
            }
            Element img = thumb.selectFirst("img");
            String cover = img == null ? null : protocolRelative(img.attr(coverAttr));
            items.add(new SearchResult(id(), normalizePath(path), title, null, cover,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        return new SeriesPage(items, !items.isEmpty());
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId);
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }

        Element cover = doc.selectFirst(".g_thumb > img");
        String title = cover != null && cover.hasAttr("alt") ? cover.attr("alt").trim() : "";
        if (title.isEmpty()) {
            Element heading = doc.selectFirst("h1");
            title = heading != null ? heading.text().trim() : seriesExternalId;
        }

        Element tag = doc.selectFirst(".det-hd-detail > .det-hd-tag");
        List<String> genres = new ArrayList<>();
        if (tag != null && tag.hasAttr("title")) {
            String value = tag.attr("title").trim();
            if (!value.isEmpty()) {
                genres.add(value);
            }
        }

        Element synopsis = doc.selectFirst(".j_synopsis");
        String summary = null;
        if (synopsis != null) {
            Element copy = synopsis.clone();
            copy.select("br").append("\n");
            summary = emptyToNull(copy.wholeText().trim());
        }

        return new SearchResult(id(), seriesExternalId, title, summary,
            cover == null ? null : protocolRelative(cover.attr("src")),
            labelledValue(doc, "Author:"), null,
            genres, parseStatus(statusText(doc)), Map.of());
    }

    /** Detail rows are label/value pairs: a {@code .c_s} caption followed by its value element. */
    private static String labelledValue(Document doc, String label) {
        for (Element caption : doc.select(".det-info .c_s")) {
            if (label.equalsIgnoreCase(caption.text().trim())) {
                Element value = caption.nextElementSibling();
                if (value != null) {
                    return emptyToNull(value.text().trim());
                }
            }
        }
        return null;
    }

    /** Status sits next to an icon that identifies itself by title rather than by a caption. */
    private static String statusText(Document doc) {
        for (Element icon : doc.select(".det-hd-detail svg")) {
            if ("Status".equalsIgnoreCase(icon.attr("title"))) {
                Element value = icon.nextElementSibling();
                if (value != null) {
                    return value.text().trim();
                }
            }
        }
        return null;
    }

    private static SeriesStatus parseStatus(String status) {
        if (status == null) {
            return SeriesStatus.UNKNOWN;
        }
        String lower = status.toLowerCase(Locale.ROOT);
        if (lower.contains("ongoing")) {
            return SeriesStatus.ONGOING;
        }
        if (lower.contains("completed")) {
            return SeriesStatus.COMPLETED;
        }
        return SeriesStatus.UNKNOWN;
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId + "/catalog");
        if (doc == null) {
            return List.of();
        }
        boolean hideLocked = settings != null && settings.getBoolean(PREF_HIDE_LOCKED, false);

        List<SourceChapter> chapters = new ArrayList<>();
        // The catalog groups chapters under volume headings; the volume is folded into each name.
        for (Element volume : doc.select(".volume-item")) {
            Matcher m = VOLUME.matcher(volume.text());
            String volumeName = m.find() ? "Volume " + m.group(1) : "Unknown Volume";

            for (Element entry : volume.select("li")) {
                Element link = entry.selectFirst("a");
                if (link == null) {
                    continue;
                }
                String path = link.attr("href");
                if (path.isBlank()) {
                    continue;
                }
                // A padlock icon marks a chapter that needs a paid unlock.
                boolean locked = entry.selectFirst("svg") != null;
                if (locked && hideLocked) {
                    continue;
                }
                String title = link.hasAttr("title") ? link.attr("title").trim() : link.text().trim();
                String name = volumeName + ": " + (title.isEmpty() ? "Untitled" : title);
                chapters.add(new SourceChapter(normalizePath(path), locked ? name + " 🔒" : name,
                    null, null, null, Map.of()));
            }
        }
        return chapters;
    }

    // ---- Content ---------------------------------------------------------------------------------

    /** BOOK sources serve text, not page images. */
    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        return List.of();
    }

    @Override
    public SourceChapterContent chapterContent(String chapterExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + chapterExternalId);
        if (doc == null) {
            return new SourceChapterContent(null, "");
        }
        // Inline reader comments are attached to paragraphs; they are not part of the prose.
        doc.select(".para-comment").remove();

        Element heading = doc.selectFirst(".cha-tit");
        Element body = doc.selectFirst(".cha-words");
        if (body == null) {
            LOG.log(System.Logger.Level.WARNING,
                () -> "Webnovel: no chapter body at " + BASE_URL + chapterExternalId);
            return new SourceChapterContent(heading == null ? null : heading.text().trim(), "");
        }
        return new SourceChapterContent(
            heading == null ? null : heading.text().trim(),
            body.html());
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** Series and chapters are keyed by site path, with the leading slash kept. */
    private static String normalizePath(String href) {
        if (href.startsWith(BASE_URL)) {
            return href.substring(BASE_URL.length());
        }
        return href.startsWith("/") ? href : "/" + href;
    }

    /** Image URLs come back protocol-relative ({@code //img…}); give them a scheme. */
    private static String protocolRelative(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (url.startsWith("http")) {
            return url;
        }
        return url.startsWith("//") ? "https:" + url : BASE_URL + url;
    }

    private Document getHtml(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            if (!res.isSuccessful() || payload == null) {
                int code = res.code();
                LOG.log(System.Logger.Level.WARNING, () -> "Webnovel HTTP " + code + " for " + url);
                return null;
            }
            return Jsoup.parse(payload, url);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Webnovel request failed for " + url, e);
            return null; // fail soft, per the SPI contract
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
