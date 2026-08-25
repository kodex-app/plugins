package dev.kodex.plugin.novelfire;

import dev.kodex.spi.MediaKind;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.common.http.ProviderRateLimitException;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Novel Fire (novelfire.net) — a web-novel source. Browsing goes through the site's advanced-search
 * form, while the chapter list comes from a DataTables-style AJAX endpoint that can return a whole
 * novel's chapters in one request.
 *
 * <p>Ported from the LNReader {@code novelfire} multisrc template (the {@code novelfire} entry).
 */
@Extension
public class NovelFireSource implements ContentSource {

    private static final String BASE_URL = "https://novelfire.net";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(NovelFireSource.class.getName());

    /** Zero-width characters the site sprinkles through chapter titles. */
    private static final String ZERO_WIDTH = "[\\u200B-\\u200D\\uFEFF]";
    /** Body text the site returns instead of an error status when it throttles. */
    private static final String THROTTLE_MARKER = "You are being rate limited";

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    /** The AJAX endpoint is DataTables-shaped and wants a monotonically increasing request counter. */
    private final java.util.concurrent.atomic.AtomicInteger draw = new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    /** Novel Fire sits behind Cloudflare, so go through the operator's solver when one is configured. */
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
        return "Novel Fire";
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
        return advancedSearch(page, Filters.defaultFilterList(Filters.DEFAULT_SORT));
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return advancedSearch(page, Filters.defaultFilterList(Filters.LATEST_SORT));
    }

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList(Filters.DEFAULT_SORT);
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            FilterList effective = (filters == null || filters.filters().isEmpty())
                ? Filters.defaultFilterList(Filters.DEFAULT_SORT)
                : filters;
            return advancedSearch(page, effective);
        }
        // A keyword uses the site's plain search, which ignores the advanced-search filters entirely.
        HttpUrl url = HttpUrl.get(BASE_URL + "/search").newBuilder()
            .addQueryParameter("keyword", q)
            .addQueryParameter("page", String.valueOf(Math.max(1, page)))
            .build();
        Document doc = getHtml(url.toString());
        return doc == null ? SeriesPage.empty()
            : parseNovels(doc, ".novel-list.chapters .novel-item");
    }

    /** The {@code /search-adv} form, which is also how the popular and latest feeds are produced. */
    private SeriesPage advancedSearch(int page, FilterList filters) {
        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/search-adv").newBuilder();
        String genreOperator = "and";
        String chapters = "0";
        String ratingOperator = "min";
        String rating = "0";
        String status = "-1";
        String sort = Filters.DEFAULT_SORT;
        String tagOperator = "and";
        String author = "";
        List<String> languages = new ArrayList<>();
        List<String> genres = new ArrayList<>();

        for (Filter<?> filter : filters.filters()) {
            if (filter instanceof Filter.Group group) {
                List<String> target = Filters.LANGUAGE.equals(group.name()) ? languages
                    : Filters.GENRES.equals(group.name()) ? genres : null;
                if (target == null) {
                    continue;
                }
                for (Filter<?> option : group.state()) {
                    if (option instanceof Filter.CheckBox box && Boolean.TRUE.equals(box.state())) {
                        String value = Filters.valueOf(group.name(), box.name());
                        if (value != null) {
                            target.add(value);
                        }
                    }
                }
            } else if (filter instanceof Filter.Select select) {
                String value = Filters.selectedValue(select);
                if (value == null) {
                    continue;
                }
                switch (select.name()) {
                    case Filters.GENRE_OPERATOR -> genreOperator = value;
                    case Filters.CHAPTERS -> chapters = value;
                    case Filters.RATING_OPERATOR -> ratingOperator = value;
                    case Filters.RATING -> rating = value;
                    case Filters.STATUS -> status = value;
                    case Filters.SORT -> sort = value;
                    case Filters.TAG_OPERATOR -> tagOperator = value;
                    default -> {
                    }
                }
            } else if (filter instanceof Filter.TextFilter text && Filters.AUTHOR.equals(text.name())) {
                author = text.state().trim();
            }
        }

        for (String language : languages) {
            url.addQueryParameter("country_id[]", language);
        }
        url.addQueryParameter("ctgcon", genreOperator);
        for (String genre : genres) {
            url.addQueryParameter("categories[]", genre);
        }
        url.addQueryParameter("totalchapter", chapters);
        url.addQueryParameter("ratcon", ratingOperator);
        url.addQueryParameter("rating", rating);
        url.addQueryParameter("status", status);
        url.addQueryParameter("sort", sort);
        url.addQueryParameter("tagcon", tagOperator);
        if (!author.isEmpty()) {
            url.addQueryParameter("author", author);
        }
        url.addQueryParameter("page", String.valueOf(Math.max(1, page)));

        Document doc = getHtml(url.build().toString());
        return doc == null ? SeriesPage.empty() : parseNovels(doc, ".novel-item");
    }

    private SeriesPage parseNovels(Document doc, String selector) {
        List<SearchResult> items = new ArrayList<>();
        for (Element card : doc.select(selector)) {
            Element link = card.selectFirst("> a");
            if (link == null) {
                link = card.selectFirst("h4 a");
            }
            if (link == null) {
                continue;
            }
            String path = pathOf(link.attr("abs:href"));
            if (path == null) {
                continue;
            }
            String name = link.hasAttr("title") ? link.attr("title") : "";
            if (name.isBlank()) {
                Element heading = card.selectFirst("h4");
                name = heading == null ? path : heading.text().trim();
            }
            Element cover = card.selectFirst(".novel-cover > img");
            String coverUrl = null;
            if (cover != null) {
                coverUrl = cover.hasAttr("data-src") ? cover.absUrl("data-src") : cover.absUrl("src");
            }
            items.add(new SearchResult(id(), path, name, null, emptyToNull(coverUrl),
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        return new SeriesPage(items, !items.isEmpty());
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + "/" + seriesExternalId);
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }

        Element titleEl = doc.selectFirst(".novel-title");
        Element coverEl = doc.selectFirst(".cover > img");
        String title = titleEl != null ? titleEl.text().trim()
            : coverEl != null ? coverEl.attr("alt") : seriesExternalId;
        String cover = null;
        if (coverEl != null) {
            cover = coverEl.hasAttr("data-src") ? coverEl.absUrl("data-src") : coverEl.absUrl("src");
        }

        List<String> genres = new ArrayList<>();
        for (Element genre : doc.select(".categories .property-item")) {
            String name = genre.text().trim();
            if (!name.isEmpty()) {
                genres.add(name);
            }
        }

        Element authorEl = doc.selectFirst(".author .property-item > span");
        String author = authorEl == null ? null : emptyToNull(authorEl.text().trim());

        Map<String, String> attributes = new LinkedHashMap<>();
        Element ratingEl = doc.selectFirst(".nub");
        if (ratingEl != null && !ratingEl.text().isBlank()) {
            attributes.put("rating", ratingEl.text().trim());
        }

        return new SearchResult(id(), seriesExternalId, title, summary(doc), emptyToNull(cover),
            author, null, genres, parseStatus(doc), attributes);
    }

    /** The blurb is an HTML block with a "read more" toggle; flatten it to paragraph-separated text. */
    private static String summary(Document doc) {
        Element content = doc.selectFirst(".summary .content");
        if (content == null) {
            return null;
        }
        Element copy = content.clone();
        copy.select(".expand").remove();
        copy.select("br").append("\n");
        copy.select("p").prepend("\n").append("\n");
        List<String> lines = new ArrayList<>();
        for (String line : copy.wholeText().split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines.isEmpty() ? null : String.join("\n\n", lines);
    }

    /** The header shows publication state as a class on a stats badge rather than as labelled text. */
    private static SeriesStatus parseStatus(Document doc) {
        Element ongoing = doc.selectFirst(".header-stats .ongoing");
        Element completed = doc.selectFirst(".header-stats .completed");
        String raw = ongoing != null && !ongoing.text().isBlank() ? ongoing.text()
            : completed != null ? completed.text() : "";
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
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
        Document doc = getHtml(BASE_URL + "/" + seriesExternalId);
        if (doc == null) {
            return List.of();
        }
        Element report = doc.selectFirst("#novel-report");
        String postId = report == null ? null : emptyToNull(report.attr("report-post_id"));
        if (postId != null) {
            List<SourceChapter> chapters = ajaxChapters(seriesExternalId, postId);
            if (!chapters.isEmpty()) {
                return chapters;
            }
        }
        // The AJAX endpoint occasionally 404s; the rendered listing is the upstream fallback.
        return scrapedChapters(seriesExternalId);
    }

    /**
     * The chapter list endpoint speaks DataTables. Asking for {@code length=-1} returns every chapter in
     * one response, which is what the core wants — it has no notion of a paged chapter list.
     */
    private List<SourceChapter> ajaxChapters(String novelPath, String postId) {
        HttpUrl url = HttpUrl.get(BASE_URL + "/ajax/listChapterDataAjax").newBuilder()
            .addQueryParameter("draw", String.valueOf(draw.incrementAndGet()))
            .addQueryParameter("columns[0][data]", "n_sort")
            .addQueryParameter("columns[0][name]", "cmm_posts_detail.n_sort")
            .addQueryParameter("columns[0][searchable]", "true")
            .addQueryParameter("columns[0][orderable]", "true")
            .addQueryParameter("columns[0][search][value]", "")
            .addQueryParameter("columns[0][search][regex]", "false")
            .addQueryParameter("columns[1][data]", "bookmark_created_at")
            .addQueryParameter("columns[1][name]", "bookmark_chapters.created_at")
            .addQueryParameter("columns[1][searchable]", "false")
            .addQueryParameter("columns[1][orderable]", "true")
            .addQueryParameter("columns[1][search][value]", "")
            .addQueryParameter("columns[1][search][regex]", "false")
            .addQueryParameter("order[0][column]", "0")
            .addQueryParameter("order[0][dir]", "asc")
            .addQueryParameter("order[0][name]", "cmm_posts_detail.n_sort")
            .addQueryParameter("start", "0")
            .addQueryParameter("length", "-1")
            .addQueryParameter("search[value]", "")
            .addQueryParameter("search[regex]", "false")
            .addQueryParameter("post_id", postId)
            .addQueryParameter("only_bookmark", "false")
            .addQueryParameter("_", String.valueOf(System.currentTimeMillis()))
            .build();

        String payload = getText(url.toString(), "application/json");
        if (payload == null) {
            return List.of();
        }
        if (payload.contains("Page Not Found 404")) {
            LOG.log(System.Logger.Level.INFO, () -> "Novel Fire: chapter ajax unavailable for " + novelPath);
            return List.of();
        }

        List<SourceChapter> chapters = new ArrayList<>();
        try {
            for (JsonNode entry : MAPPER.readTree(payload).path("data")) {
                String rawName = text(entry.get("title"));
                if (rawName == null || rawName.isBlank()) {
                    rawName = text(entry.get("slug"));
                }
                JsonNode sortNode = entry.get("n_sort");
                if (rawName == null || sortNode == null || sortNode.isNull()) {
                    continue;
                }
                Double number = parseDouble(sortNode.asString());
                if (number == null) {
                    continue;
                }
                // Titles arrive as HTML and carry zero-width padding.
                String name = Jsoup.parseBodyFragment(rawName).wholeText().replaceAll(ZERO_WIDTH, "").trim();
                if (name.isEmpty()) {
                    continue;
                }
                String number$ = number == Math.floor(number)
                    ? String.valueOf((long) (double) number)
                    : String.valueOf(number);
                chapters.add(new SourceChapter(novelPath + "/chapter-" + number$, name, number,
                    null, null, Map.of()));
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Novel Fire: unreadable chapter payload for " + novelPath, e);
            return List.of();
        }
        chapters.sort(Comparator.comparing(SourceChapter::number,
            Comparator.nullsLast(Comparator.<Double>naturalOrder())));
        return chapters;
    }

    /** Walks the rendered {@code /chapters} listing when the AJAX endpoint is unavailable. */
    private List<SourceChapter> scrapedChapters(String novelPath) {
        List<SourceChapter> chapters = new ArrayList<>();
        int page = 1;
        while (true) {
            Document doc = getHtml(BASE_URL + "/" + novelPath + "/chapters?page=" + page);
            if (doc == null) {
                break;
            }
            var rows = doc.select(".chapter-list li a[href]");
            if (rows.isEmpty()) {
                break;
            }
            for (Element link : rows) {
                String path = pathOf(link.attr("abs:href"));
                if (path == null) {
                    continue;
                }
                String name = link.hasAttr("title") ? link.attr("title") : link.text().trim();
                chapters.add(new SourceChapter(path, name, null, null, null, Map.of()));
            }
            page++;
            if (page > 200) {
                break; // safety valve against an endlessly repeating listing
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

    /**
     * Fails loudly when the chapter body is missing or empty instead of returning blank text: the site drops
     * {@code #content} on transient fetch hiccups, and a silent empty chapter gets cached and downloaded as a
     * blank page. Matches upstream lnreader-plugins #2462.
     */
    @Override
    public SourceChapterContent chapterContent(String chapterExternalId, ProviderSettings settings) {
        String url = BASE_URL + "/" + chapterExternalId;
        Document doc = getHtml(url);
        if (doc == null) {
            throw new IllegalStateException("Novel Fire: chapter page could not be fetched (" + url + ") - retry");
        }
        Element content = doc.getElementById("content");
        if (content == null) {
            LOG.log(System.Logger.Level.WARNING, () -> "Novel Fire: no #content body at " + url);
            throw new IllegalStateException(
                "Novel Fire: chapter content container (#content) not found at " + url
                    + " - likely a transient fetch issue, retry");
        }
        // The site salts the prose with custom <nf…> elements holding junk text; drop them.
        for (Element element : content.getAllElements()) {
            String tag = element.tagName();
            if (tag.length() > 5 && tag.startsWith("nf")) {
                element.remove();
            }
        }
        content.select("script, style, ins, .adsbygoogle").remove();
        String html = content.html().replace("&nbsp;", " ");
        if (html.isBlank()) {
            throw new IllegalStateException("Novel Fire: chapter content was empty after parsing " + url);
        }
        Element heading = doc.selectFirst(".chapter-title");
        String title = heading == null ? null : heading.text().trim();
        return new SourceChapterContent(title, html);
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** Novels and chapters are keyed by site path without the leading slash, matching upstream. */
    private static String pathOf(String absoluteUrl) {
        if (absoluteUrl == null || absoluteUrl.isBlank()) {
            return null;
        }
        try {
            String path = java.net.URI.create(absoluteUrl).getRawPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return null;
            }
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (Exception e) {
            return null;
        }
    }

    private Document getHtml(String url) {
        String payload = getText(url, "text/html,application/xhtml+xml,*/*");
        if (payload == null) {
            return null;
        }
        Document doc = Jsoup.parse(payload, url);
        if (doc.title() != null && doc.title().contains("Cloudflare")) {
            LOG.log(System.Logger.Level.WARNING, () ->
                "Novel Fire is behind a Cloudflare block for " + url
                    + " — configure a Cloudflare solver (FlareSolverr/Byparr) in Kodex's network settings");
            return null;
        }
        return doc;
    }

    private String getText(String url, String accept) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .header("Accept", accept)
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            throwIfThrottled(res.code(), payload);
            if (!res.isSuccessful() || payload == null) {
                int code = res.code();
                LOG.log(System.Logger.Level.WARNING, () -> "Novel Fire HTTP " + code + " for " + url);
                return null;
            }
            return payload;
        } catch (ProviderRateLimitException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Novel Fire request failed for " + url, e);
            return null; // fail soft, per the SPI contract
        }
    }

    /**
     * Novel Fire throttles chapter-list requests hard, and signals it both with 429 and with a 200 whose
     * body says so. Either way the core should back off and retry rather than lose the chapter.
     */
    private static void throwIfThrottled(int code, String payload) {
        boolean throttled = code == 429 || (payload != null && payload.contains(THROTTLE_MARKER));
        if (throttled) {
            throw new ProviderRateLimitException("Novel Fire rate limit (HTTP " + code + ")", null);
        }
    }

    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asString() : null;
    }

    private static Double parseDouble(String value) {
        try {
            return value == null || value.isBlank() ? null : Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
