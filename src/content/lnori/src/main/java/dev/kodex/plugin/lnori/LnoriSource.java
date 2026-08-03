package dev.kodex.plugin.lnori;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class LnoriSource implements ContentSource {

    private static final String BASE_URL = "https://lnori.com";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int PAGE_SIZE = 36;
    private static final long LIBRARY_TTL_MS = 10 * 60 * 1000;

    private final OkHttpClient fallback = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    /** A parsed library card. {@code year} is the release year ({@code data-d}), 0 when unknown. */
    private record LibraryNovel(String path, String name, String cover, String author, List<String> tags, int year) {
    }

    private volatile List<LibraryNovel> libraryCache;
    private volatile long libraryExpiresAt;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.cloudflareClient() : fallback;
    }

    @Override
    public MediaKind kind() {
        return MediaKind.BOOK;
    }

    @Override
    public String displayName() {
        return "LNORI";
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
    public boolean supportsLatest() {
        return true;
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return filtered(null, page, Filters.defaultFilterList());
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        // "Latest" = newest by release year (the site's "Year Released" / data-sort="date").
        return filtered(null, page, Filters.dateFilterList());
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        // Filtered browsing (sort / genre) arrives here too — with or without a keyword — so apply the
        // filters, not just the text term. Fall back to defaults when no filter state was sent.
        FilterList effective = filters != null && !filters.filters().isEmpty() ? filters : Filters.defaultFilterList();
        return filtered(query, page, effective);
    }

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList();
    }

    /** Text term (optional) → genre filter → sort → paginate, all over the cached library. */
    private SeriesPage filtered(String query, int page, FilterList filters) {
        List<LibraryNovel> list = new ArrayList<>(library());

        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!term.isEmpty()) {
            list.removeIf(n -> !(n.name().toLowerCase(Locale.ROOT).contains(term)
                || (n.author() != null && n.author().toLowerCase(Locale.ROOT).contains(term))
                || n.tags().stream().anyMatch(t -> t.contains(term))));
        }

        String genre = Filters.genreValue(filters);
        if (genre != null && !genre.isEmpty()) {
            list.removeIf(n -> !n.tags().contains(genre.toLowerCase(Locale.ROOT)));
        }

        String sort = Filters.sortValue(filters);
        if ("title-az".equals(sort)) {
            list.sort(Comparator.comparing(LibraryNovel::name, String.CASE_INSENSITIVE_ORDER));
        } else if ("title-za".equals(sort)) {
            list.sort(Comparator.comparing(LibraryNovel::name, String.CASE_INSENSITIVE_ORDER).reversed());
        } else if ("date".equals(sort)) {
            // "Year Released" — newest year first (matches the site's data-sort="date"); unknown years last.
            list.sort(Comparator.comparingInt((LibraryNovel n) -> n.year() == 0 ? Integer.MIN_VALUE : n.year()).reversed());
        }
        return pageOf(list, page);
    }

    private SeriesPage pageOf(List<LibraryNovel> list, int page) {
        int from = Math.max(0, (Math.max(1, page) - 1) * PAGE_SIZE);
        if (from >= list.size()) {
            return SeriesPage.empty();
        }
        int to = Math.min(list.size(), from + PAGE_SIZE);
        List<SearchResult> items = new ArrayList<>();
        for (LibraryNovel n : list.subList(from, to)) {
            items.add(new SearchResult(id(), n.path(), n.name(), null, n.cover(),
                n.author() == null || n.author().isBlank() ? null : n.author(), null,
                List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        return new SeriesPage(items, to < list.size());
    }

    /** The parsed {@code /library} catalogue, cached briefly (it's a large single page). */
    private List<LibraryNovel> library() {
        long now = System.currentTimeMillis();
        List<LibraryNovel> cached = libraryCache;
        if (cached != null && libraryExpiresAt > now) {
            return cached;
        }
        Document doc = getHtml(BASE_URL + "/library");
        List<LibraryNovel> list = new ArrayList<>();
        if (doc != null) {
            for (Element card : doc.select("article.card")) {
                String name = card.attr("data-t").trim();
                String path = toPath(card.select("a.stretched-link").attr("href"));
                if (name.isEmpty() || path == null) {
                    continue;
                }
                String author = card.attr("data-a").trim();
                List<String> tags = new ArrayList<>();
                for (String t : card.attr("data-tags").split(",")) {
                    String tag = t.trim().toLowerCase(Locale.ROOT);
                    if (!tag.isEmpty()) {
                        tags.add(tag);
                    }
                }
                Element img = card.selectFirst(".card-cover img");
                String cover = img != null ? absUrl(firstNonBlank(img.attr("src"), img.attr("data-src"))) : null;
                int year = parseInt(card.attr("data-d")); // data-d = release year (the site's "date"/Year Released sort)
                list.add(new LibraryNovel(path, name, cover, author, tags, year));
            }
        }
        if (!list.isEmpty()) {
            libraryCache = list;
            libraryExpiresAt = now + LIBRARY_TTL_MS;
        }
        return list;
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + "/" + strip(seriesExternalId));
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        Element titleEl = doc.selectFirst(".hero-card h1.s-title");
        String title = titleEl != null ? titleEl.text().trim() : seriesExternalId;
        Element img = doc.selectFirst(".hero-card .cover-wrap img");
        String cover = img != null ? absUrl(firstNonBlank(img.attr("src"), img.attr("data-src"))) : null;
        Element authorEl = doc.selectFirst(".hero-card p.author");
        String author = authorEl != null ? authorEl.text().trim() : null;

        List<String> genres = doc.select("nav.tags-box.desktop a, nav.tags-box a").eachText()
            .stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();

        List<String> summaryParts = new ArrayList<>();
        for (Element p : doc.select("section.desc-box p.description")) {
            String t = p.text().trim();
            if (!t.isEmpty()) {
                summaryParts.add(t);
            }
        }
        String summary = String.join("\n\n", summaryParts);
        return new SearchResult(id(), seriesExternalId, title,
            summary.isBlank() ? null : summary, cover,
            author == null || author.isBlank() ? null : author, null, genres, SeriesStatus.UNKNOWN, Map.of());
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        List<SourceChapter> chapters = new ArrayList<>();
        Document doc = getHtml(BASE_URL + "/" + strip(seriesExternalId));
        if (doc == null) {
            return chapters;
        }
        // One entry per volume — parse only the series page's volume links; don't fetch each /book/ page
        // here (that would be N requests per refresh). A volume's full text is fetched lazily in
        // chapterContent when the user actually reads it. Unique hrefs, keeping the longest link text
        // (used to name the volume).
        Map<String, String> volumes = new LinkedHashMap<>();
        for (Element a : doc.select("a[href^=/book/]")) {
            String href = a.attr("href");
            String text = a.text().trim().replaceAll("\\s+", " ");
            volumes.merge(href, text, (existing, next) -> next.length() > existing.length() ? next : existing);
        }

        // Several entries can parse to the same vol-N (side stories, reissues, split parts), which used to
        // yield multiple identically-named "Volume N" rows. Count them first so repeats can be qualified
        // with their own slug; the core keeps them in this list's order regardless.
        Map<Integer, Integer> seenPerNumber = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, String> vol : volumes.entrySet()) {
            index++;
            Integer volNum = volumeNumber(vol.getKey());
            int occurrence = volNum == null ? 1 : seenPerNumber.merge(volNum, 1, Integer::sum);
            chapters.add(new SourceChapter(strip(vol.getKey()), volumeName(vol.getKey(), vol.getValue(), occurrence),
                (double) (volNum != null ? volNum : index), null, null, Map.of()));
        }
        return chapters;
    }

    private static final Pattern VOLUME_NUM = Pattern.compile("(?i)vol(?:ume)?[-_ ]?(\\d+)");

    /** The volume number parsed from the {@code /book/<id>/<slug>} slug (its trailing {@code vol-N}/{@code volume-N}), or {@code null}. */
    private static Integer volumeNumber(String href) {
        String[] parts = href.replaceAll("/+$", "").split("/");
        String slug = parts.length > 0 ? parts[parts.length - 1] : "";
        Matcher m = VOLUME_NUM.matcher(slug);
        Integer last = null; // the last match, so a series title containing "vol" doesn't win over the trailing volume marker
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // keep previous
            }
        }
        return last;
    }

    /**
     * Display name for a volume. {@code occurrence} is how many entries with this same volume number have
     * been seen so far (1 for the first): the site can list several books that all parse to {@code vol-N},
     * and naming them all "Volume N" left rows indistinguishable, so repeats are qualified with the part
     * of their slug that actually differs.
     */
    private static String volumeName(String href, String text, int occurrence) {
        // Prefer the slug's volume number ("Volume N", matching the site) — the hero "Start Reading" link's
        // text is the full series title, which the old "longest text" heuristic wrongly picked for volume 1.
        Integer volNum = volumeNumber(href);
        if (volNum != null) {
            return occurrence <= 1 ? "Volume " + volNum : "Volume " + volNum + " (" + volumeQualifier(href, occurrence) + ")";
        }
        String clean = text == null ? "" : text.replaceAll("(?i)Start Reading", "").trim();
        if (!clean.isEmpty()) {
            return clean;
        }
        String[] parts = href.replaceAll("/+$", "").split("/");
        String slug = parts.length > 0 ? parts[parts.length - 1] : "";
        StringBuilder sb = new StringBuilder();
        for (String w : slug.split("-")) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return sb.toString().trim();
    }

    /**
     * A short label distinguishing repeated volume numbers: the slug's words with the series/volume noise
     * dropped (e.g. {@code .../side-stories-vol-3} → "Side Stories"). Falls back to the occurrence index
     * when nothing distinctive is left, so the name is always unique within the series.
     */
    private static String volumeQualifier(String href, int occurrence) {
        String[] parts = href.replaceAll("/+$", "").split("/");
        String slug = parts.length > 0 ? parts[parts.length - 1] : "";
        StringBuilder sb = new StringBuilder();
        for (String w : slug.split("-")) {
            // Drop the volume marker itself and its number — that's the part they all share.
            if (w.isEmpty() || w.matches("(?i)vol|volume") || w.matches("\\d+")) {
                continue;
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        String qualifier = sb.toString().trim();
        return qualifier.isEmpty() ? "#" + occurrence : qualifier;
    }

    // ---- Content (BOOK) --------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        return List.of(); // BOOK source: text comes from chapterContent
    }

    @Override
    public SourceChapterContent chapterContent(String chapterExternalId, ProviderSettings settings) {
        // Entries are whole volumes now; ignore any legacy '#anchor' and return the full volume page.
        int hash = chapterExternalId.indexOf('#');
        String volPath = hash < 0 ? chapterExternalId : chapterExternalId.substring(0, hash);

        Document doc = getHtml(BASE_URL + "/" + strip(volPath));
        if (doc == null) {
            return new SourceChapterContent(null, "");
        }
        Element h1 = doc.selectFirst("h1");
        String title = h1 != null ? h1.text().trim() : null;

        // Concatenate every chapter section in order, keeping the per-chapter headings so the volume shows
        // its internal structure in the reader.
        StringBuilder html = new StringBuilder();
        for (Element section : doc.select("section.chapter")) {
            html.append(sectionHtml(section)).append('\n');
        }
        return new SourceChapterContent(title, html.toString());
    }

    /** The readable HTML of a chapter section (heading kept), with scripts/nav stripped and images absolutized. */
    private static String sectionHtml(Element section) {
        Element content = section.clone();
        content.select("script, style, nav").remove();
        for (Element img : content.select("img")) {
            String src = img.attr("src");
            if (src.startsWith("/")) {
                img.attr("src", BASE_URL + src);
            }
        }
        for (Element source : content.select("source")) {
            String srcset = source.attr("srcset");
            if (srcset.startsWith("/")) {
                source.attr("srcset", BASE_URL + srcset);
            }
        }
        return content.html();
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
        throw new ProviderRateLimitException("LNORI rate limit (HTTP 429)", retryAfter);
    }

    /** Strips scheme+host from an absolute url, keeping the path; strips a leading slash from a relative path. */
    private static String toPath(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String path = href;
        if (href.startsWith("http")) {
            try {
                path = URI.create(href).getRawPath();
            } catch (Exception e) {
                path = href;
            }
        }
        return path == null ? null : path.replaceAll("^/+", "");
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
        if (src.startsWith("//")) {
            return "https:" + src;
        }
        return BASE_URL + (src.startsWith("/") ? src : "/" + src);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** First run of digits in {@code s} as an int (e.g. {@code data-d} year), or 0 if none. */
    private static int parseInt(String s) {
        if (s == null) {
            return 0;
        }
        Matcher m = Pattern.compile("\\d+").matcher(s);
        try {
            return m.find() ? Integer.parseInt(m.group()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
