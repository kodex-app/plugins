package dev.kodex.plugin.ranobes;

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
import dev.kodex.spi.content.filter.FilterList;
import dev.kodex.spi.common.http.SourceUnavailableException;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ranobes (ranobes.top) — an English web-novel source, scraped from HTML. The chapter list is the one
 * structured part: each listing page embeds a {@code window.__DATA__} JSON blob that carries the whole
 * page of chapters plus the total page count.
 *
 * <p>Ported from the LNReader {@code ranobes} multisrc template (the {@code ranobes} entry; the site's
 * Russian sibling at ranobes.com uses the same template with a different base URL and path).
 */
@Extension
public class RanobesSource implements ContentSource {

    private static final String BASE_URL = "https://ranobes.top";
    /** The site section novels live under, and the segment stripped to reach the chapter listing. */
    private static final String NOVELS_PATH = "novels";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(RanobesSource.class.getName());

    /** Covers are set as a CSS background on the card's {@code <figure>}. */
    private static final Pattern CSS_URL = Pattern.compile("url\\((['\"]?)(.*?)\\1\\)");
    /** The chapter listing ships as a JSON assignment inside a script tag. */
    private static final Pattern DATA_BLOB = Pattern.compile("window\\.__DATA__\\s*=\\s*(\\{.*)", Pattern.DOTALL);
    /** Relative chapter timestamps, e.g. "3 days ago". */
    private static final Pattern RELATIVE_DATE =
        Pattern.compile("(\\d+)\\s+(minute|minutes|hour|hours|day|days|month|months|year|years)\\s+ago",
            Pattern.CASE_INSENSITIVE);
    /** Titles the site serves in place of content when it wants a human to prove themselves. */
    private static final List<String> CHALLENGE_TITLES = List.of(
        "Bot Verification", "You are being redirected...", "Un instant...",
        "Just a moment...", "Redirecting...");

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    /** Ranobes sits behind Cloudflare, so go through the operator's solver when one is configured. */
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
        return "Ranobes";
    }

    @Override
    public String website() {
        return BASE_URL;
    }

    @Override
    public String language() {
        return "en";
    }

    /** The site has no separate "recently updated" feed — only the ranked novel index and search. */
    @Override
    public boolean supportsLatest() {
        return false;
    }

    @Override
    public FilterList getFilterList() {
        return FilterList.empty();
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return novelList(BASE_URL + "/" + NOVELS_PATH + "/page/" + Math.max(1, page) + "/");
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return popular(page, settings);
        }
        return novelList(BASE_URL + "/search/" + enc(q) + "/page/" + Math.max(1, page));
    }

    private SeriesPage novelList(String url) {
        Document doc = getHtml(url);
        List<SearchResult> items = new ArrayList<>();
        for (Element card : doc.select(".short-cont")) {
            Element link = card.selectFirst("h2.title a");
            if (link == null) {
                continue;
            }
            String path = relative(link.attr("abs:href"));
            String cover = null;
            Element figure = card.selectFirst("figure");
            if (figure != null) {
                Matcher m = CSS_URL.matcher(figure.attr("style"));
                if (m.find()) {
                    cover = absolute(m.group(2));
                }
            }
            items.add(new SearchResult(id(), path, link.text().trim(), null, cover,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        // The index paginates indefinitely; a full-looking page implies another one exists.
        return new SeriesPage(items, !items.isEmpty());
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId);

        String title = null;
        String cover = null;
        Element poster = doc.selectFirst(".poster img");
        if (poster != null) {
            title = emptyToNull(poster.attr("alt"));
            cover = absolute(poster.attr("src"));
        }
        if (title == null) {
            Element heading = doc.selectFirst("h1");
            title = heading != null ? heading.text().trim() : seriesExternalId;
        }

        Element summaryEl = doc.selectFirst("[itemprop=description]");
        if (summaryEl == null) {
            summaryEl = doc.selectFirst("div.moreless.cont-text.showcont-h");
        }
        String summary = summaryEl == null ? null : emptyToNull(summaryEl.wholeText().trim());

        Element authorEl = doc.selectFirst("[itemprop=creator]");
        String author = authorEl == null ? null : emptyToNull(authorEl.text().trim());

        List<String> genres = new ArrayList<>();
        for (Element genre : doc.select("#mc-fs-genre a")) {
            String name = genre.text().trim();
            if (!name.isEmpty()) {
                genres.add(name);
            }
        }

        // "Original status" is a list item whose link reads Ongoing or Completed.
        SeriesStatus status = SeriesStatus.UNKNOWN;
        Element statusEl = doc.selectFirst("li[title*=Original status] a");
        if (statusEl != null) {
            status = "Ongoing".equalsIgnoreCase(statusEl.text().trim())
                ? SeriesStatus.ONGOING
                : SeriesStatus.COMPLETED;
        }

        return new SearchResult(id(), seriesExternalId, title, summary, cover,
            author, null, genres, status, Map.of());
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        String listingBase = chapterListingUrl(seriesExternalId);
        if (listingBase == null) {
            return List.of();
        }

        List<SourceChapter> chapters = new ArrayList<>();
        int page = 1;
        int pageCount = 1;
        do {
            Document doc = getHtml(listingBase + "/page/" + page);
            JsonNode data = dataBlob(doc);
            if (data == null) {
                // No embedded blob — fall back to the rendered listing.
                collectFromHtml(doc, chapters);
                break;
            }
            if (page == 1) {
                pageCount = Math.max(1, data.path("pages_count").asInt(1));
            }
            for (JsonNode chapter : data.path("chapters")) {
                String link = text(chapter.get("link"));
                if (link == null) {
                    continue;
                }
                chapters.add(new SourceChapter(relative(link), text(chapter.get("title")), null, null,
                    parseDate(text(chapter.get("date"))), Map.of()));
            }
            page++;
        } while (page <= pageCount);

        // The listing runs newest-first; the core wants oldest-first reading order.
        Collections.reverse(chapters);
        return chapters;
    }

    /**
     * The chapter index lives at {@code /chapters/<id>}, where the id is the numeric prefix of the novel's
     * own path ({@code /novels/12345-some-slug.html} → {@code /chapters/12345}).
     */
    private static String chapterListingUrl(String seriesExternalId) {
        String path = seriesExternalId.startsWith("/") ? seriesExternalId : "/" + seriesExternalId;
        int dash = path.indexOf('-');
        String idPart = dash < 0 ? path : path.substring(0, dash);
        idPart = idPart.replace("/" + NOVELS_PATH + "/", "/");
        if (idPart.isBlank() || "/".equals(idPart)) {
            return null;
        }
        return BASE_URL + "/chapters" + idPart;
    }

    private static JsonNode dataBlob(Document doc) {
        for (Element script : doc.select("script")) {
            String body = script.data();
            if (!body.contains("window.__DATA__")) {
                continue;
            }
            Matcher m = DATA_BLOB.matcher(body);
            if (!m.find()) {
                continue;
            }
            try {
                // The assignment may be followed by more script; let Jackson read just the object.
                return MAPPER.readTree(m.group(1));
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, () -> "Ranobes: unreadable __DATA__ blob", e);
            }
        }
        return null;
    }

    /** Fallback chapter scrape for pages that render the listing without the JSON blob. */
    private static void collectFromHtml(Document doc, List<SourceChapter> chapters) {
        for (Element link : doc.select("div.cat_block.cat_line a[title][href]")) {
            chapters.add(new SourceChapter(relative(link.attr("abs:href")), link.attr("title"),
                null, null, null, Map.of()));
        }
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
        Element article = doc.getElementById("arrticle");
        if (article == null) {
            LOG.log(System.Logger.Level.WARNING,
                () -> "Ranobes: no #arrticle body at " + BASE_URL + chapterExternalId);
            return new SourceChapterContent(null, "");
        }
        // Strip the site chrome that sits inside the article body.
        article.select("script, style, .category, .grey, ins, .adsbygoogle").remove();
        Element heading = doc.selectFirst("h1");
        return new SourceChapterContent(heading == null ? null : heading.text().trim(), article.html());
    }

    // ---- Helpers ---------------------------------------------------------------------------------

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
                throw SourceUnavailableException.http(displayName(), url, res.code(), payload);
            }
            Document doc = Jsoup.parse(payload, url);
            // The bot check answers 200 with an interstitial, so it has to be spotted by title.
            String title = doc.title() == null ? "" : doc.title().trim();
            if (CHALLENGE_TITLES.contains(title)) {
                throw SourceUnavailableException.unreadable(displayName(), url, "bot check \"" + title
                    + "\" — configure a Cloudflare solver (FlareSolverr/Byparr) in Kodex's network settings");
            }
            return doc;
        } catch (RuntimeException e) {
            throw e; // already the right failure (unavailable / rate limit)
        } catch (Exception e) {
            throw SourceUnavailableException.transport(displayName(), url, e);
        }
    }

    /** Series and chapters are keyed by site path, so absolute links are trimmed back to one. */
    private static String relative(String url) {
        if (url == null) {
            return null;
        }
        if (url.startsWith(BASE_URL)) {
            return url.substring(BASE_URL.length());
        }
        return url.startsWith("/") ? url : "/" + url;
    }

    private static String absolute(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (url.startsWith("http")) {
            return url;
        }
        return BASE_URL + (url.startsWith("/") ? url : "/" + url);
    }

    /**
     * Chapter timestamps are relative ("3 days ago"). Anything under a day still falls on today, so only
     * day-and-coarser units move the date.
     */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Matcher m = RELATIVE_DATE.matcher(value.trim());
        if (m.find()) {
            int amount = Integer.parseInt(m.group(1));
            return switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "minute", "minutes", "hour", "hours" -> today;
                case "day", "days" -> today.minusDays(amount);
                case "month", "months" -> today.minusMonths(amount);
                case "year", "years" -> today.minusYears(amount);
                default -> null;
            };
        }
        try {
            return value.length() >= 10 ? LocalDate.parse(value.substring(0, 10)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asString() : null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
