package dev.kodex.plugin.comick;

import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.common.http.ProviderRateLimitException;
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
import org.pf4j.Extension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Comick (comick.live) — the "Comick (Unoriginal)" source. Browse and search come from the JSON API
 * under {@code /api}, while series details and page lists are read out of JSON blobs embedded in the
 * site's HTML ({@code #comic-data} and {@code #sv-data}).
 *
 * <p>Comick publishes one Mihon source per translated language; this plugin mirrors that with an
 * {@code @Extension} subclass per language (see the bottom of this file). {@link #displayName()} is
 * shared across all of them, so the recomputed {@link #id()} matches Mihon's per-language id.
 */
public abstract class ComickSource implements ContentSource {

    private static final String BASE_URL = "https://comick.live";
    private static final int LATEST_PAGE_SIZE = 100;
    /** {@code /api/comics/top} is a fixed six-page carousel, not an open-ended feed. */
    private static final int POPULAR_PAGES = 6;
    private static final int MIN_QUERY_LENGTH = 3;
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(ComickSource.class.getName());

    /** Collapses runs of whitespace before the sentence-splitting passes below. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Breaks paragraphs after sentence-ending periods, skipping common abbreviations. */
    private static final Pattern SENTENCE_END =
        Pattern.compile("(?<=[^.]{12})(?<!\\bMr|\\bMs|\\bMrs|\\bDr|\\bProf|\\bSr|\\bJr|\\bVol|\\bCh)\\.\\s+");
    /** Breaks paragraphs after colons that end a clause rather than a short label. */
    private static final Pattern CLAUSE_END = Pattern.compile("(?<=[^:]{12})(?<!\\b[a-zA-Z]{1,10}):\\s+");
    /** Splits a comma-separated manual tag entry; a leading dash excludes the tag. */
    private static final Pattern SPACE_OR_SLASH = Pattern.compile("[ /]");

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    /** {@code /api/metadata} genre and tag slugs, fetched once and reused for the filter list. */
    private volatile Map<String, String> genreSlugs;
    private volatile Map<String, String> tagSlugs;

    /**
     * Comick's search pages with an opaque cursor rather than an offset, so page N+1 can only be
     * fetched once page N has been read. This remembers the cursor the last search handed back,
     * keyed by the query it belongs to.
     */
    private volatile CursorState cursorState;

    private record CursorState(String key, int nextPage, String cursor) {
    }

    private final String lang;

    protected ComickSource(String lang) {
        this.lang = lang;
    }

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    /**
     * Use the Cloudflare-aware client: most of Comick's API answers anonymously, but {@code /api/search}
     * sits behind a Cloudflare challenge, so those requests go through the operator's FlareSolverr/Byparr
     * (a no-op falling back to the plain client when none is configured).
     */
    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.cloudflareClient() : FALLBACK;
    }

    @Override
    public String displayName() {
        return "Comick (Unoriginal)";
    }

    @Override
    public String website() {
        return BASE_URL;
    }

    @Override
    public String language() {
        return lang;
    }

    // ---- Browse ----------------------------------------------------------------------------------

    /**
     * The site's "top" carousel: three follow-count windows (7/30/90 days), each in an all-time and a
     * new-series flavour, exposed as six pages.
     */
    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        int index = Math.max(1, page);
        if (index > POPULAR_PAGES) {
            return SeriesPage.empty();
        }
        int days = switch ((index - 1) % 3) {
            case 0 -> 7;
            case 1 -> 30;
            default -> 90;
        };
        String type = index <= 3 ? "follow" : "most_follow_new";
        JsonNode root = getJson(BASE_URL + "/api/comics/top?days=" + days + "&type=" + type);
        if (root == null) {
            return SeriesPage.empty();
        }
        return new SeriesPage(mapComics(root.path("data")), index < POPULAR_PAGES);
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        JsonNode root = getJson(BASE_URL + "/api/chapters/latest?order=new&page=" + Math.max(1, page));
        if (root == null) {
            return SeriesPage.empty();
        }
        JsonNode data = root.path("data");
        return new SeriesPage(mapComics(data), data.size() == LATEST_PAGE_SIZE);
    }

    // ---- Filters ---------------------------------------------------------------------------------

    /**
     * Comick serves its genre and tag vocabulary from {@code /api/metadata}, so the two tri-state groups
     * are built from the live response instead of a table baked into this plugin. When that call fails
     * the groups are replaced by a free-text tag box, which is the upstream fallback.
     */
    @Override
    public FilterList getFilterList() {
        loadMetadata();
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Sort(Filters.SORT, Filters.SORT_LABELS, new Filter.Sort.Selection(0, false)));
        filters.add(Filters.checkBoxGroup(Filters.DEMOGRAPHIC, Filters.DEMOGRAPHIC_OPTIONS));
        filters.add(Filters.checkBoxGroup(Filters.TYPE, Filters.TYPE_OPTIONS));

        Map<String, String> genres = genreSlugs;
        Map<String, String> tags = tagSlugs;
        if (genres != null && !genres.isEmpty()) {
            filters.add(Filters.triStateGroup(Filters.GENRE, genres));
        }
        if (tags != null && !tags.isEmpty()) {
            filters.add(Filters.triStateGroup(Filters.TAGS, tags));
        } else {
            filters.add(new Filter.Header("Separate tags with commas (,)"));
            filters.add(new Filter.Header("Prepend with a dash (-) to exclude"));
            filters.add(new Filter.TextFilter(Filters.TAGS));
        }

        filters.add(Filters.select(Filters.CREATED_AT, Filters.CREATED_AT_OPTIONS));
        filters.add(new Filter.TextFilter(Filters.MIN_CHAPTERS));
        filters.add(Filters.select(Filters.STATUS, Filters.STATUS_OPTIONS));
        filters.add(Filters.select(Filters.CONTENT_RATING, Filters.CONTENT_RATING_OPTIONS));
        filters.add(Filters.select(Filters.RELEASE_FROM, Filters.releaseYearOptions()));
        filters.add(Filters.select(Filters.RELEASE_TO, Filters.releaseYearOptions()));
        return new FilterList(filters);
    }

    private void loadMetadata() {
        if (genreSlugs != null) {
            return;
        }
        JsonNode root = getJson(BASE_URL + "/api/metadata");
        if (root == null) {
            return;
        }
        genreSlugs = slugsByName(root.path("genres"));
        tagSlugs = slugsByName(root.path("tags"));
    }

    private static Map<String, String> slugsByName(JsonNode array) {
        Map<String, String> slugs = Filters.newOrderedMap();
        for (JsonNode entry : array) {
            String name = text(entry.get("name"));
            String slug = text(entry.get("slug"));
            if (name != null && slug != null) {
                slugs.put(name, slug);
            }
        }
        return Map.copyOf(slugs);
    }

    // ---- Search ----------------------------------------------------------------------------------

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty() && q.length() < MIN_QUERY_LENGTH) {
            LOG.log(System.Logger.Level.INFO,
                () -> "Comick search needs at least " + MIN_QUERY_LENGTH + " characters — ignoring \"" + q + "\"");
            return SeriesPage.empty();
        }
        FilterList effective = (filters == null || filters.filters().isEmpty()) ? getFilterList() : filters;
        int index = Math.max(1, page);

        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/api/search").newBuilder();
        boolean applied = applyFilters(url, effective);
        if (!applied) {
            return SeriesPage.empty();
        }
        url.addQueryParameter("showAll", "false");
        url.addQueryParameter("exclude_mylist", "false");
        if (!q.isEmpty()) {
            url.addQueryParameter("q", q);
        }
        url.addQueryParameter("type", "comic");

        // The query itself identifies the cursor chain; a different query restarts from page 1.
        String key = url.build().toString();
        if (index > 1) {
            CursorState state = cursorState;
            if (state == null || !state.key().equals(key) || state.nextPage() != index) {
                // No cursor for this page (non-sequential paging, or a different query) — nothing to fetch.
                return SeriesPage.empty();
            }
            url.addQueryParameter("cursor", state.cursor());
        }

        JsonNode root = getJson(url.build().toString());
        if (root == null) {
            return SeriesPage.empty();
        }
        String nextCursor = text(root.get("next_cursor"));
        cursorState = nextCursor == null ? null : new CursorState(key, index + 1, nextCursor);
        return new SeriesPage(mapComics(root.path("data")), nextCursor != null);
    }

    /** Writes the filter state onto the search URL. Returns false when a filter value is unusable. */
    private boolean applyFilters(HttpUrl.Builder url, FilterList filters) {
        for (Filter<?> filter : filters.filters()) {
            if (filter instanceof Filter.Sort sort && Filters.SORT.equals(sort.name())) {
                Filter.Sort.Selection selection = sort.state();
                int index = selection == null ? 0 : selection.index();
                if (index >= 0 && index < Filters.SORT_VALUES.size()) {
                    url.addQueryParameter("order_by", Filters.SORT_VALUES.get(index));
                }
                url.addQueryParameter("order_direction",
                    selection != null && selection.ascending() ? "asc" : "desc");
            } else if (filter instanceof Filter.Group group) {
                switch (group.name()) {
                    case Filters.GENRE -> applyTriState(url, group, genreSlugs, "genres", "excludes");
                    case Filters.TAGS -> applyTriState(url, group, tagSlugs, "tags", "excluded_tags");
                    case Filters.DEMOGRAPHIC -> applyChecked(url, group, "demographic");
                    case Filters.TYPE -> applyChecked(url, group, "country");
                    default -> {
                    }
                }
            } else if (filter instanceof Filter.Select select) {
                String value = Filters.selectedValue(select);
                if (value == null) {
                    continue;
                }
                switch (select.name()) {
                    case Filters.CREATED_AT -> url.addQueryParameter("time", value);
                    case Filters.STATUS -> url.addQueryParameter("status", value);
                    case Filters.CONTENT_RATING -> url.addQueryParameter("content_rating", value);
                    case Filters.RELEASE_FROM -> url.addQueryParameter("from", value);
                    case Filters.RELEASE_TO -> url.addQueryParameter("to", value);
                    default -> {
                    }
                }
            } else if (filter instanceof Filter.TextFilter text) {
                String state = text.state().trim();
                if (state.isEmpty()) {
                    continue;
                }
                if (Filters.MIN_CHAPTERS.equals(text.name())) {
                    if (!state.chars().allMatch(Character::isDigit)) {
                        LOG.log(System.Logger.Level.INFO,
                            () -> "Comick: invalid minimum chapters value \"" + state + "\"");
                        return false;
                    }
                    url.addQueryParameter("minimum", state);
                } else if (Filters.TAGS.equals(text.name())) {
                    applyManualTags(url, state);
                }
            }
        }
        return true;
    }

    /** The free-text tag fallback: {@code "school life, -isekai"} → one include and one exclude. */
    private static void applyManualTags(HttpUrl.Builder url, String state) {
        for (String raw : state.split(",")) {
            String value = SPACE_OR_SLASH.matcher(raw.trim().toLowerCase(java.util.Locale.ROOT))
                .replaceAll("-");
            if (value.isBlank()) {
                continue;
            }
            if (value.startsWith("-")) {
                url.addQueryParameter("excluded_tags", value.substring(1));
            } else {
                url.addQueryParameter("tags", value);
            }
        }
    }

    private static void applyTriState(HttpUrl.Builder url, Filter.Group group, Map<String, String> slugs,
                                      String includeParam, String excludeParam) {
        if (slugs == null) {
            return;
        }
        for (Filter<?> option : group.state()) {
            if (!(option instanceof Filter.TriState tri)) {
                continue;
            }
            String slug = slugs.get(tri.name());
            if (slug == null) {
                continue;
            }
            if (tri.isIncluded()) {
                url.addQueryParameter(includeParam, slug);
            } else if (tri.isExcluded()) {
                url.addQueryParameter(excludeParam, slug);
            }
        }
    }

    private static void applyChecked(HttpUrl.Builder url, Filter.Group group, String param) {
        for (Filter<?> option : group.state()) {
            if (option instanceof Filter.CheckBox box && Boolean.TRUE.equals(box.state())) {
                String value = Filters.valueOf(group.name(), box.name());
                if (value != null) {
                    url.addQueryParameter(param, value);
                }
            }
        }
    }

    private List<SearchResult> mapComics(JsonNode array) {
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode comic : array) {
            String slug = text(comic.get("slug"));
            if (slug == null) {
                continue;
            }
            results.add(new SearchResult(id(), slug, orElse(text(comic.get("title")), slug), null,
                text(comic.get("default_thumbnail")), null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        return results;
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        SearchResult fallback = new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
            null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        JsonNode data = embeddedJson(BASE_URL + "/comic/" + seriesExternalId, "comic-data");
        if (data == null) {
            return fallback;
        }

        String title = orElse(text(data.get("title")), seriesExternalId);
        boolean translationCompleted = data.path("translation_completed").asBoolean(false);
        SeriesStatus status = switch (data.path("status").asInt(0)) {
            case 1 -> SeriesStatus.ONGOING;
            case 2 -> translationCompleted ? SeriesStatus.COMPLETED : SeriesStatus.PUBLISHING_FINISHED;
            case 3 -> SeriesStatus.CANCELLED;
            case 4 -> SeriesStatus.ON_HIATUS;
            default -> SeriesStatus.UNKNOWN;
        };

        StringBuilder description = new StringBuilder(cleanDescription(text(data.get("desc"))));
        List<String> altTitles = new ArrayList<>();
        // "md_titles" is an array most of the time, but degrades to an id-keyed object.
        for (JsonNode entry : data.path("md_titles")) {
            String alt = text(entry.get("title"));
            if (alt != null && !alt.isBlank()) {
                altTitles.add("- " + alt.trim());
            }
        }
        if (!altTitles.isEmpty()) {
            if (!description.isEmpty()) {
                description.append("\n\n");
            }
            description.append("Alternative Titles:\n").append(String.join("\n", altTitles));
        }

        List<String> genres = new ArrayList<>();
        String country = text(data.get("country"));
        if (country != null) {
            switch (country) {
                case "jp" -> genres.add("Manga");
                case "cn" -> genres.add("Manhua");
                case "ko" -> genres.add("Manhwa");
                default -> {
                }
            }
        }
        String contentRating = text(data.get("content_rating"));
        if ("suggestive".equals(contentRating) || "erotica".equals(contentRating)) {
            genres.add("Content Rating: " + Character.toUpperCase(contentRating.charAt(0)) + contentRating.substring(1));
        }
        for (JsonNode entry : data.path("md_comic_md_genres")) {
            String name = text(entry.path("md_genres").get("name"));
            if (name != null) {
                genres.add(name);
            }
        }

        return new SearchResult(id(), seriesExternalId, title,
            description.isEmpty() ? null : description.toString(),
            text(data.get("default_thumbnail")),
            joinNames(data.path("authors")),
            joinNames(data.path("artists")),
            genres, status, Map.of());
    }

    /**
     * Comick stores descriptions as HTML with the paragraph breaks stripped out. Undo that: flatten the
     * markup, collapse whitespace, then re-break on sentence-ending periods and clause-ending colons.
     */
    private static String cleanDescription(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = Jsoup.parseBodyFragment(html).wholeText();
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = SENTENCE_END.matcher(text).replaceAll(".\n\n");
        text = CLAUSE_END.matcher(text).replaceAll(":\n\n");
        return text.trim();
    }

    private static String joinNames(JsonNode array) {
        List<String> names = new ArrayList<>();
        for (JsonNode entry : array) {
            String name = text(entry.get("name"));
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        List<SourceChapter> chapters = new ArrayList<>();
        int page = 1;
        int lastPage = 1;
        do {
            String url = BASE_URL + "/api/comics/" + seriesExternalId + "/chapter-list?lang=" + lang
                + "&page=" + page;
            JsonNode root = getJson(url);
            if (root == null) {
                break;
            }
            for (JsonNode chapter : root.path("data")) {
                SourceChapter mapped = toChapter(seriesExternalId, chapter);
                if (mapped != null) {
                    chapters.add(mapped);
                }
            }
            lastPage = root.path("pagination").path("last_page").asInt(page);
            page++;
        } while (page <= lastPage);
        return chapters;
    }

    private SourceChapter toChapter(String slug, JsonNode chapter) {
        String hid = text(chapter.get("hid"));
        String chap = text(chapter.get("chap"));
        if (hid == null) {
            return null;
        }
        String chapterLang = orElse(text(chapter.get("lang")), lang);
        StringBuilder name = new StringBuilder();
        String volume = text(chapter.get("vol"));
        if (volume != null && !volume.isBlank()) {
            name.append("Vol. ").append(volume).append(' ');
        }
        name.append("Ch. ").append(chap == null ? "" : chap);
        String title = text(chapter.get("title"));
        if (title != null && !title.isBlank()) {
            name.append(": ").append(title);
        }

        List<String> groups = new ArrayList<>();
        for (JsonNode group : chapter.path("group_name")) {
            String value = text(group);
            if (value != null && !value.isBlank()) {
                groups.add(value);
            }
        }

        return new SourceChapter(
            "/comic/" + slug + "/" + hid + "-chapter-" + (chap == null ? "" : chap) + "-" + chapterLang,
            name.toString(),
            parseDouble(chap),
            groups.isEmpty() ? null : String.join(", ", groups),
            parseDate(text(chapter.get("created_at"))),
            Map.of());
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        JsonNode data = embeddedJson(BASE_URL + chapterExternalId, "sv-data");
        if (data == null) {
            return List.of();
        }
        Map<String, String> headers = Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
        List<SourcePage> pages = new ArrayList<>();
        int index = 0;
        for (JsonNode image : data.path("chapter").path("images")) {
            String url = text(image.get("url"));
            if (url != null && !url.isBlank()) {
                pages.add(new SourcePage(index++, url, headers));
            }
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /**
     * Reads the JSON payload the site embeds in a {@code <script id="...">} tag. Comick renders both the
     * series details and the chapter's image list this way rather than exposing them on the API.
     */
    private JsonNode embeddedJson(String url, String elementId) {
        Document doc = getHtml(url);
        if (doc == null) {
            return null;
        }
        Element holder = doc.getElementById(elementId);
        if (holder == null) {
            LOG.log(System.Logger.Level.WARNING, () -> "Comick: no #" + elementId + " payload at " + url);
            return null;
        }
        try {
            return MAPPER.readTree(holder.data());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Comick: unreadable #" + elementId + " payload at " + url, e);
            return null;
        }
    }

    private Document getHtml(String url) {
        try (Response res = execute(url, "text/html,application/xhtml+xml,*/*")) {
            if (res == null) {
                return null;
            }
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            if (!res.isSuccessful() || payload == null) {
                logFailure(res.code(), payload, url);
                return null;
            }
            return Jsoup.parse(payload, url);
        } catch (ProviderRateLimitException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Comick request failed for " + url, e);
            return null; // fail soft, per the SPI contract
        }
    }

    private JsonNode getJson(String url) {
        try (Response res = execute(url, "application/json")) {
            if (res == null) {
                return null;
            }
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            if (!res.isSuccessful() || payload == null) {
                logFailure(res.code(), payload, url);
                return null;
            }
            return MAPPER.readTree(payload);
        } catch (ProviderRateLimitException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Comick request failed for " + url, e);
            return null; // fail soft, per the SPI contract
        }
    }

    private Response execute(String url, String accept) throws java.io.IOException {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .header("Accept", accept)
            .get().build();
        Response res = http().newCall(req).execute();
        throwIfRateLimited(res);
        return res;
    }

    /**
     * A Cloudflare challenge answers 403/503 with an interstitial page rather than the API payload, which
     * on its own just looks like a permission error. Name it, so the operator knows the fix is to point
     * Kodex at a Cloudflare solver rather than to go hunting for credentials.
     */
    private static void logFailure(int code, String payload, String url) {
        boolean challenged = (code == 403 || code == 503)
            && payload != null && payload.contains("Just a moment...");
        if (challenged) {
            LOG.log(System.Logger.Level.WARNING, () ->
                "Comick returned a Cloudflare challenge (HTTP " + code + ") for " + url
                    + " — configure a Cloudflare solver (FlareSolverr/Byparr) in Kodex's network settings");
        } else {
            LOG.log(System.Logger.Level.WARNING, () -> "Comick HTTP " + code + " for " + url);
        }
    }

    /**
     * Signals a 429 to the core so a library scan backs off and retries instead of failing the chapter —
     * Comick rate-limits hard (the upstream extension throttles itself to one request every two seconds).
     */
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
        res.close();
        throw new ProviderRateLimitException("Comick rate limit (HTTP 429)", retryAfter);
    }

    /** A scalar JSON node's text, or null when the field is absent/null/non-scalar. */
    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asString() : null;
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Double parseDouble(String value) {
        try {
            return value == null || value.isBlank() ? null : Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String iso) {
        try {
            return iso != null && iso.length() >= 10 ? LocalDate.parse(iso.substring(0, 10)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Per-language sources (one @Extension each, mirroring Comick's Mihon source list) ---------

    @Extension public static final class English extends ComickSource { public English() { super("en"); } }

    @Extension public static final class Russian extends ComickSource { public Russian() { super("ru"); } }

    @Extension public static final class Vietnamese extends ComickSource { public Vietnamese() { super("vi"); } }

    @Extension public static final class French extends ComickSource { public French() { super("fr"); } }

    @Extension public static final class Polish extends ComickSource { public Polish() { super("pl"); } }

    @Extension public static final class Indonesian extends ComickSource { public Indonesian() { super("id"); } }

    @Extension public static final class Turkish extends ComickSource { public Turkish() { super("tr"); } }

    @Extension public static final class Italian extends ComickSource { public Italian() { super("it"); } }

    @Extension public static final class Spanish extends ComickSource { public Spanish() { super("es"); } }

    @Extension public static final class Ukrainian extends ComickSource { public Ukrainian() { super("uk"); } }

    @Extension public static final class German extends ComickSource { public German() { super("de"); } }

    @Extension public static final class Korean extends ComickSource { public Korean() { super("ko"); } }

    @Extension public static final class Thai extends ComickSource { public Thai() { super("th"); } }

    @Extension public static final class Romanian extends ComickSource { public Romanian() { super("ro"); } }

    @Extension public static final class Malay extends ComickSource { public Malay() { super("ms"); } }

    @Extension public static final class Japanese extends ComickSource { public Japanese() { super("ja"); } }

    @Extension public static final class Swedish extends ComickSource { public Swedish() { super("sv"); } }

    @Extension public static final class Norwegian extends ComickSource { public Norwegian() { super("no"); } }
}
