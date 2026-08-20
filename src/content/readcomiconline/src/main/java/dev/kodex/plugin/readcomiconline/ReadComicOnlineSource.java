package dev.kodex.plugin.readcomiconline;

import dev.kodex.spi.PluginConfigSchema;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.content.ContentSource;
import dev.kodex.spi.content.SearchResult;
import dev.kodex.spi.content.SeriesPage;
import dev.kodex.spi.content.SeriesStatus;
import dev.kodex.spi.content.SourceChapter;
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
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.pf4j.Extension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReadComicOnline (readcomiconline.li) — a scraped HTML comic source. Browsing, search, details and
 * chapter lists are read straight off the site's pages; page images are recovered from the reader page's
 * obfuscated JavaScript by {@link ImageDecryptor}.
 *
 * <p>Ported from the Keiyoushi {@code ReadComicOnline} extension.
 */
@Extension
public class ReadComicOnlineSource implements ContentSource {

    private static final String BASE_URL = "https://readcomiconline.li";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(ReadComicOnlineSource.class.getName());

    /** The upstream decryptor config; consulted for its flags and to detect that the port has gone stale. */
    private static final String REMOTE_CONFIG_URL =
        "https://raw.githubusercontent.com/keiyoushi/rco-script/refs/heads/main/decrypt.json";

    private static final DateTimeFormatter CHAPTER_DATE =
        DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.ENGLISH);
    private static final Pattern VIEWS = Pattern.compile("Views:\\s*([\\d,]+)");

    static final String PREF_QUALITY = "image_quality";
    static final String PREF_SERVER = "image_server";
    private static final String QUALITY_DEFAULT = "hq";
    private static final String SERVER_1 = "Server 1";
    private static final String SERVER_2 = "Server 2";

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    private volatile RemoteConfig remoteConfig;
    private volatile boolean staleWarningLogged;

    /** The upstream {@code decrypt.json}: flags this port honours, plus the script it was ported from. */
    private record RemoteConfig(String imageDecryptEval, String postDecryptEval, boolean shouldVerifyLinks) {
    }

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    /**
     * ReadComicOnline sits behind Cloudflare, so challenged requests go through the operator's
     * FlareSolverr/Byparr (a no-op falling back to the plain client when none is configured).
     */
    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.cloudflareClient() : FALLBACK;
    }

    @Override
    public String displayName() {
        return "ReadComicOnline";
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
            new PluginConfigSchema.Field(PREF_QUALITY, "Image quality",
                PluginConfigSchema.FieldType.ENUM, false, QUALITY_DEFAULT, List.of("hq", "lq")),
            new PluginConfigSchema.Field(PREF_SERVER, "Image server",
                PluginConfigSchema.FieldType.ENUM, false, SERVER_1, List.of(SERVER_1, SERVER_2))));
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return comicList(BASE_URL + "/ComicList/MostPopular?page=" + Math.max(1, page));
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return comicList(BASE_URL + "/ComicList/LatestUpdate?page=" + Math.max(1, page));
    }

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList();
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        String q = query == null ? "" : query.trim();
        FilterList effective = (filters == null || filters.filters().isEmpty()) ? getFilterList() : filters;
        return comicList(searchUrl(q, Math.max(1, page), effective));
    }

    /**
     * The site exposes three different browse routes and search only works on one of them, so pick the
     * narrowest that can express the current filters — mirroring the upstream extension:
     * a single included genre browses {@code /Genre/…}, a bare sort/publisher/writer/artist browses
     * {@code /ComicList} or {@code /Publisher/…}, and anything richer goes through {@code /AdvanceSearch}.
     */
    private String searchUrl(String query, int page, FilterList filters) {
        List<String> includedGenres = new ArrayList<>();
        List<String> excludedGenres = new ArrayList<>();
        List<String> includedGenreNames = new ArrayList<>();
        String status = "";
        String sort = null;
        String year = null;
        String publisher = "";
        String writer = "";
        String artist = "";

        for (Filter<?> filter : filters.filters()) {
            if (filter instanceof Filter.Group group && Filters.GENRES.equals(group.name())) {
                for (Filter<?> option : group.state()) {
                    if (!(option instanceof Filter.TriState tri)) {
                        continue;
                    }
                    String genreId = Filters.genreId(tri.name());
                    if (genreId == null) {
                        continue;
                    }
                    if (tri.isIncluded()) {
                        includedGenres.add(genreId);
                        includedGenreNames.add(tri.name());
                    } else if (tri.isExcluded()) {
                        excludedGenres.add(genreId);
                    }
                }
            } else if (filter instanceof Filter.Select select) {
                String value = Filters.selectedValue(select);
                switch (select.name()) {
                    case Filters.STATUS -> status = value == null ? "" : value;
                    case Filters.SORT -> sort = value;
                    case Filters.YEAR -> year = value;
                    default -> {
                    }
                }
            } else if (filter instanceof Filter.TextFilter text) {
                String value = text.state().trim();
                switch (text.name()) {
                    case Filters.PUBLISHER -> publisher = value;
                    case Filters.WRITER -> writer = value;
                    case Filters.ARTIST -> artist = value;
                    default -> {
                    }
                }
            }
        }

        boolean plainBrowse = query.isEmpty() && excludedGenres.isEmpty() && year == null;

        if (plainBrowse && includedGenres.size() == 1) {
            HttpUrl.Builder url = HttpUrl.get(BASE_URL).newBuilder()
                .addPathSegment("Genre")
                .addPathSegment(includedGenreNames.get(0).replace(" ", "-"));
            if (sort != null) {
                url.addPathSegment(sort);
            }
            return url.addQueryParameter("page", String.valueOf(page)).build().toString();
        }

        if (plainBrowse && includedGenres.isEmpty()) {
            HttpUrl.Builder url = HttpUrl.get(BASE_URL).newBuilder();
            // Only the first of publisher/writer/artist is honoured — they are separate site sections.
            String byPerson = firstNonEmpty(
                "Publisher", publisher, "Writer", writer, "Artist", artist);
            if (byPerson != null) {
                url.addPathSegments(byPerson);
            } else {
                url.addPathSegment("ComicList");
            }
            if (sort != null) {
                url.addPathSegment(sort);
            }
            return url.addQueryParameter("page", String.valueOf(page)).build().toString();
        }

        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/AdvanceSearch").newBuilder()
            .addQueryParameter("comicName", query)
            .addQueryParameter("page", String.valueOf(page))
            .addQueryParameter("status", status)
            .addQueryParameter("ig", String.join(",", includedGenres))
            .addQueryParameter("eg", String.join(",", excludedGenres));
        if (year != null) {
            url.addQueryParameter("pubDate", year);
        }
        return url.build().toString();
    }

    /** {@code "Publisher/Marvel-Comics"} for the first filled section, or null when all are empty. */
    private static String firstNonEmpty(String... sectionsAndValues) {
        for (int i = 0; i + 1 < sectionsAndValues.length; i += 2) {
            String value = sectionsAndValues[i + 1];
            if (value != null && !value.isEmpty()) {
                return sectionsAndValues[i] + "/" + value.replace(" ", "-");
            }
        }
        return null;
    }

    private SeriesPage comicList(String url) {
        Document doc = getHtml(url);
        if (doc == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        for (Element link : doc.select(".list-comic > .item > a:first-child")) {
            Element cover = link.selectFirst("img");
            items.add(new SearchResult(id(), relative(link.attr("abs:href")), link.text(), null,
                cover == null ? null : cover.attr("abs:src"),
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        boolean hasNextPage = doc.selectFirst("ul.pager > li > a:contains(Next)") != null;
        return new SeriesPage(items, hasNextPage);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        SearchResult fallback = new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
            null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        Document doc = getHtml(BASE_URL + seriesExternalId);
        if (doc == null) {
            return fallback;
        }
        Element info = doc.selectFirst("div.barContent");
        if (info == null) {
            return fallback;
        }

        Element titleEl = info.selectFirst("a.bigChar");
        String title = titleEl == null ? seriesExternalId : titleEl.text();
        String artist = summarize(info.select("p:has(span:contains(Artist:)) > a").eachText());
        String author = summarize(info.select("p:has(span:contains(Writer:)) > a").eachText());
        List<String> genres = List.copyOf(info.select("p:has(span:contains(Genres:)) > a").eachText());

        List<String> descriptionParts = new ArrayList<>();
        String summary = joinPreservingLineBreaks(info.select("p:has(span:contains(Summary:)) ~ p"));
        if (!summary.isEmpty()) {
            descriptionParts.add(summary);
        }
        String publisher = info.select("p:has(span:contains(Publisher:))").text();
        if (!publisher.isEmpty()) {
            descriptionParts.add("\n" + publisher);
        }
        String published = info.select("p:has(span:contains(Publication date:))").text();
        if (!published.isEmpty()) {
            descriptionParts.add(published);
        }
        Matcher views = VIEWS.matcher(info.select("p:has(span:contains(Views:))").text());
        if (views.find()) {
            descriptionParts.add("Views: " + views.group(1));
        }

        Element statusEl = info.selectFirst("p:has(span:contains(Status:))");
        SeriesStatus status = parseStatus(statusEl == null ? "" : statusEl.text());

        // The cover sits in the page's first right-hand box.
        Element cover = doc.selectFirst(".rightBox:eq(0) img");
        if (cover == null) {
            cover = doc.selectFirst(".rightBox img");
        }

        return new SearchResult(id(), seriesExternalId, title,
            descriptionParts.isEmpty() ? null : String.join("\n", descriptionParts),
            cover == null ? null : cover.absUrl("src"),
            author.isEmpty() ? null : author,
            artist.isEmpty() ? null : artist,
            genres, status, Map.of());
    }

    /** Long credit lists are noise on a card: keep the first name and say there are more. */
    private static String summarize(List<String> names) {
        if (names.size() > 2) {
            return names.get(0) + " & others";
        }
        return String.join(", ", names);
    }

    /**
     * The summary is a run of {@code <p>} blocks that use {@code <br>} for its line breaks;
     * {@code text()} would flatten those into spaces, so swap them for real newlines first.
     */
    private static String joinPreservingLineBreaks(Elements paragraphs) {
        List<String> blocks = new ArrayList<>();
        for (Element paragraph : paragraphs) {
            Element copy = paragraph.clone();
            for (Element br : copy.select("br")) {
                br.replaceWith(new TextNode("\n"));
            }
            blocks.add(copy.wholeText());
        }
        return String.join("\n\n", blocks).trim();
    }

    private static SeriesStatus parseStatus(String status) {
        if (status.contains("Ongoing")) {
            return SeriesStatus.ONGOING;
        }
        if (status.contains("Completed")) {
            return SeriesStatus.COMPLETED;
        }
        return SeriesStatus.UNKNOWN;
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId);
        if (doc == null) {
            return List.of();
        }
        List<SourceChapter> chapters = new ArrayList<>();
        // Row 0 is the table header, row 1 the column labels — the listing starts at row 2.
        for (Element row : doc.select("table.listing tr:gt(1)")) {
            Element link = row.selectFirst("a");
            if (link == null) {
                continue;
            }
            Element dateCell = row.selectFirst("td:eq(1)");
            chapters.add(new SourceChapter(
                relative(link.attr("abs:href")),
                link.text(),
                null,
                null,
                parseDate(dateCell == null ? null : dateCell.text()),
                Map.of()));
        }
        return chapters;
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        boolean useServer2 = SERVER_2.equals(settings == null ? SERVER_1 : settings.getString(PREF_SERVER, SERVER_1));
        String quality = settings == null ? QUALITY_DEFAULT : settings.getString(PREF_QUALITY, QUALITY_DEFAULT);

        String url = BASE_URL + chapterExternalId
            + "&s=" + (useServer2 ? "s2" : "") + "&quality=" + quality + "&readType=1";
        Document doc = getHtml(url);
        if (doc == null) {
            return List.of();
        }

        // The de-obfuscation regexes span the whole page, so feed every inline script in as one blob.
        StringBuilder scripts = new StringBuilder();
        for (Element script : doc.select("script")) {
            scripts.append(script.data().stripIndent()).append('\n');
        }

        RemoteConfig config = remoteConfig();
        List<String> imageUrls = ImageDecryptor.extractImageUrls(scripts.toString(), useServer2);
        if (imageUrls.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING,
                () -> "ReadComicOnline: no page images recovered from " + url
                    + " — the site's obfuscation may have changed (see ImageDecryptor)");
            return List.of();
        }

        Map<String, String> headers = Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
        boolean verify = config != null && config.shouldVerifyLinks();
        List<SourcePage> pages = new ArrayList<>();
        int index = 0;
        for (String imageUrl : imageUrls) {
            if (verify && !linkResolves(imageUrl)) {
                continue; // upstream drops links the CDN no longer serves
            }
            pages.add(new SourcePage(index++, imageUrl, headers));
        }
        return pages;
    }

    private boolean linkResolves(String imageUrl) {
        Request req = new Request.Builder().url(imageUrl).head()
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .build();
        try (Response res = http().newCall(req).execute()) {
            return res.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
    }

    /**
     * Fetches upstream's decryptor config once per source instance. Its flags are honoured directly, and
     * its script is hashed against the one {@link ImageDecryptor} was ported from — a mismatch means the
     * site changed and the port needs refreshing, which is worth saying out loud in the server log.
     */
    private RemoteConfig remoteConfig() {
        RemoteConfig cached = remoteConfig;
        if (cached != null) {
            return cached;
        }
        JsonNode root = getJson(REMOTE_CONFIG_URL + "?bust=" + System.currentTimeMillis());
        if (root == null) {
            return null;
        }
        JsonNode postDecrypt = root.get("postDecryptEval");
        RemoteConfig config = new RemoteConfig(
            root.path("imageDecryptEval").asString(""),
            postDecrypt == null || postDecrypt.isNull() ? null : postDecrypt.asString(),
            root.path("shouldVerifyLinks").asBoolean(false));
        remoteConfig = config;
        warnIfPortIsStale(config);
        return config;
    }

    private void warnIfPortIsStale(RemoteConfig config) {
        if (staleWarningLogged) {
            return;
        }
        String liveHash = ImageDecryptor.sha256(config.imageDecryptEval());
        if (!ImageDecryptor.PORTED_SCRIPT_SHA256.equals(liveHash)) {
            staleWarningLogged = true;
            LOG.log(System.Logger.Level.WARNING, () ->
                "ReadComicOnline: upstream's image decryptor changed (expected SHA-256 "
                    + ImageDecryptor.PORTED_SCRIPT_SHA256 + ", found " + liveHash
                    + "). Page images may be wrong until ImageDecryptor is re-ported from "
                    + REMOTE_CONFIG_URL);
        } else if (config.postDecryptEval() != null) {
            staleWarningLogged = true;
            LOG.log(System.Logger.Level.WARNING, () ->
                "ReadComicOnline: upstream added a post-decrypt step that this port does not implement; "
                    + "page images may be wrong");
        }
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
                int code = res.code();
                LOG.log(System.Logger.Level.WARNING, () -> "ReadComicOnline HTTP " + code + " for " + url);
                return null;
            }
            // The site answers a bot check with its "are you human" page instead of an error status.
            if (res.request().url().encodedPath().startsWith("/Special/AreYouHuman")) {
                LOG.log(System.Logger.Level.WARNING, () ->
                    "ReadComicOnline served its captcha page for " + url
                        + " — solve it in a browser, or configure a Cloudflare solver in Kodex's network settings");
                return null;
            }
            return Jsoup.parse(payload, url);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "ReadComicOnline request failed for " + url, e);
            return null; // fail soft, per the SPI contract
        }
    }

    private JsonNode getJson(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            if (!res.isSuccessful() || payload == null) {
                int code = res.code();
                LOG.log(System.Logger.Level.WARNING, () -> "ReadComicOnline HTTP " + code + " for " + url);
                return null;
            }
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "ReadComicOnline request failed for " + url, e);
            return null;
        }
    }

    /** Series and chapters are keyed by site path, matching what Mihon stores. */
    private static String relative(String absoluteUrl) {
        try {
            URI uri = URI.create(absoluteUrl);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (Exception e) {
            return absoluteUrl;
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), CHAPTER_DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
