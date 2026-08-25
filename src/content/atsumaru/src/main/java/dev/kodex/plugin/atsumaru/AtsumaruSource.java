package dev.kodex.plugin.atsumaru;

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
import org.pf4j.Extension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Atsumaru (atsu.moe) — a JSON-API comic source. Browse feeds come from {@code /api/home2/*}, search
 * queries the site's Typesense collection directly, and details/chapters/pages come from
 * {@code /api/manga/*} and {@code /api/read/chapter}.
 *
 * <p>Ported from the Keiyoushi {@code Atsumaru} extension. {@link #versionId()} is pinned to 2 to match
 * it, so the recomputed {@link #id()} equals Mihon's and {@code .tachibk} imports line up.
 */
@Extension
public class AtsumaruSource implements ContentSource {

    private static final String BASE_URL = "https://atsu.moe";
    private static final int BROWSE_LIMIT = 40;
    private static final int SEARCH_LIMIT = 40;
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(AtsumaruSource.class.getName());

    /** Opt-in 18+ browsing, mirroring the extension's "Toggle adult mode" preference. */
    static final String PREF_SHOW_18 = "show_adult";

    /** How long a fetched {@code /api/explore/availableFilters} response is reused. */
    private static final long FILTER_TTL_MS = 6L * 60 * 60 * 1000;

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;
    private volatile Filters.Data filterData;
    private volatile long filterDataExpiresAt;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.httpClient() : FALLBACK;
    }

    @Override
    public String displayName() {
        return "Atsumaru";
    }

    @Override
    public String website() {
        return BASE_URL;
    }

    @Override
    public String language() {
        return "en";
    }

    /** Matches the upstream extension's {@code versionId = 2} so {@link #id()} equals Mihon's. */
    @Override
    public int versionId() {
        return 2;
    }

    @Override
    public PluginConfigSchema configSchema() {
        return new PluginConfigSchema(List.of(
            PluginConfigSchema.Field.bool(PREF_SHOW_18, "Show adult content (18+)", false)));
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return browse("popular", page, settings, "&timeframe=daily");
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return browse("recentlyUpdated", page, settings, "");
    }

    private SeriesPage browse(String feed, int page, ProviderSettings settings, String extraParams) {
        int offset = (Math.max(1, page) - 1) * BROWSE_LIMIT;
        String url = BASE_URL + "/api/home2/" + feed
            + "?offset=" + offset + "&limit=" + BROWSE_LIMIT
            + "&types=Manga,Manwha,Manhua,OEL&mediums=Comic" + extraParams + adultParam(settings);
        JsonNode root = getJson(url);
        if (root == null) {
            return SeriesPage.empty();
        }
        // The browse feeds report no total, so upstream always advertises another page.
        return new SeriesPage(mapItems(root.path("items")), true);
    }

    @Override
    public FilterList getFilterList() {
        return Filters.filterList(filterData(), false);
    }

    /**
     * The filter tables the site publishes, cached for {@link #FILTER_TTL_MS}. Falls back to the offline
     * tables in {@link Filters} when the call fails, and keeps serving a stale copy rather than shrinking
     * the UI's filter list on a transient error.
     */
    private Filters.Data filterData() {
        Filters.Data cached = filterData;
        if (cached != null && System.currentTimeMillis() < filterDataExpiresAt) {
            return cached;
        }
        JsonNode root = getJson(BASE_URL + "/api/explore/availableFilters");
        Filters.Data parsed = root == null ? null : Filters.parse(root);
        if (parsed == null) {
            return cached != null ? cached : Filters.fallback();
        }
        filterData = parsed;
        filterDataExpiresAt = System.currentTimeMillis() + FILTER_TTL_MS;
        return parsed;
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        String q = query == null ? "" : query.trim();
        boolean force18 = is18Enabled(settings);
        Filters.Data data = filterData();
        FilterList effective = (filters == null || filters.filters().isEmpty())
            ? Filters.filterList(data, force18)
            : filters;

        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/collections/manga/documents/search").newBuilder();
        url.addQueryParameter("q", q.isEmpty() ? "*" : q);

        List<String> includedGenres = new ArrayList<>();
        List<String> excludedGenres = new ArrayList<>();
        List<String> includedTags = new ArrayList<>();
        List<String> excludedTags = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        Integer year = null;
        Integer minChapters = null;
        boolean showAdult = force18;
        boolean officialOnly = false;
        String sortBy = "";

        for (Filter<?> filter : effective.filters()) {
            if (filter instanceof Filter.Group group) {
                switch (group.name()) {
                    case Filters.GENRES ->
                        collectTriState(data, Filters.GENRES, group, includedGenres, excludedGenres);
                    case Filters.TAGS ->
                        collectTriState(data, Filters.TAGS, group, includedTags, excludedTags);
                    case Filters.TYPE -> collectChecked(data, Filters.TYPE, group, types);
                    case Filters.STATUS -> collectChecked(data, Filters.STATUS, group, statuses);
                    default -> {
                    }
                }
            } else if (filter instanceof Filter.TextFilter text) {
                if (Filters.YEAR.equals(text.name())) {
                    year = parseInt(text.state());
                } else if (Filters.MIN_CHAPTERS.equals(text.name())) {
                    minChapters = parseInt(text.state());
                }
            } else if (filter instanceof Filter.Sort sort && Filters.SORT.equals(sort.name())) {
                Filter.Sort.Selection selection = sort.state();
                int index = selection == null ? 0 : selection.index();
                boolean ascending = selection != null && selection.ascending();
                if (index >= 0 && index < Filters.SORT_VALUES.size()) {
                    sortBy = Filters.SORT_VALUES.get(index) + ":" + (ascending ? "asc" : "desc");
                }
            } else if (filter instanceof Filter.CheckBox box) {
                if (Filters.ADULT.equals(box.name())) {
                    showAdult = box.state() || force18;
                } else if (Filters.OFFICIAL.equals(box.name())) {
                    officialOnly = box.state();
                }
            }
        }

        List<String> filterBy = new ArrayList<>();
        filterBy.add("hidden:!=true");
        if (!includedGenres.isEmpty()) {
            filterBy.add(joinIncludes(includedGenres, "genreIds"));
        }
        if (!excludedGenres.isEmpty()) {
            filterBy.add("genreIds:!=[" + backtickList(excludedGenres) + "]");
        }
        if (!includedTags.isEmpty()) {
            filterBy.add(joinIncludes(includedTags, "tagIds"));
        }
        if (!excludedTags.isEmpty()) {
            filterBy.add("tagIds:!=[" + backtickList(excludedTags) + "]");
        }
        if (!types.isEmpty()) {
            filterBy.add("type:=[" + backtickList(types) + "]");
        }
        if (!statuses.isEmpty()) {
            filterBy.add("status:=[" + backtickList(statuses) + "]");
        }
        if (year != null) {
            filterBy.add("releaseYear:=[" + year + "]");
        }
        if (minChapters != null) {
            filterBy.add("chapterCount:>=" + minChapters);
        }
        if (!showAdult) {
            filterBy.add("isAdult:=false");
        }
        if (officialOnly) {
            filterBy.add("officialTranslation:=true");
        }
        filterBy.add("(mbContentRating:=[`Safe`,`Suggestive`,`Erotica`] || mbContentRating:!=*)");
        filterBy.add("medium:!=[`Novel`]");
        filterBy.add("views:>0");

        url.addQueryParameter("filter_by", String.join(" && ", filterBy));
        if (!sortBy.isEmpty()) {
            url.addQueryParameter("sort_by", sortBy);
        }
        if (!q.isEmpty()) {
            url.addQueryParameter("query_by", "title,englishTitle,otherNames,authors");
            url.addQueryParameter("query_by_weights", "4,3,2,1");
            url.addQueryParameter("num_typos", "4,3,2,1");
        }
        url.addQueryParameter("page", String.valueOf(Math.max(1, page)));
        url.addQueryParameter("per_page", String.valueOf(SEARCH_LIMIT));

        JsonNode root = getJson(url.build().toString());
        if (root == null) {
            return SeriesPage.empty();
        }
        // Typesense answers with "hits"; anything else is the plain browse shape.
        if (root.has("hits")) {
            List<SearchResult> items = new ArrayList<>();
            for (JsonNode hit : root.path("hits")) {
                items.add(toResult(hit.path("document")));
            }
            int found = root.path("found").asInt(0);
            int currentPage = root.path("page").asInt(1);
            int perPage = root.path("request_params").path("per_page").asInt(SEARCH_LIMIT);
            return new SeriesPage(items, (long) currentPage * perPage < found);
        }
        return new SeriesPage(mapItems(root.path("items")), true);
    }

    /** Walks a filter group (tags nest one subgroup per category) collecting the included/excluded ids. */
    private static void collectTriState(Filters.Data data, String kind, Filter.Group group,
        List<String> included, List<String> excluded) {
        for (Filter<?> option : group.state()) {
            if (option instanceof Filter.Group nested) {
                collectTriState(data, kind, nested, included, excluded);
            } else if (option instanceof Filter.TriState tri) {
                String id = data.idOf(kind, tri.name());
                if (id == null) {
                    continue;
                }
                if (tri.isIncluded()) {
                    included.add(id);
                } else if (tri.isExcluded()) {
                    excluded.add(id);
                }
            }
        }
    }

    private static void collectChecked(Filters.Data data, String kind, Filter.Group group, List<String> out) {
        for (Filter<?> option : group.state()) {
            if (option instanceof Filter.Group nested) {
                collectChecked(data, kind, nested, out);
            } else if (option instanceof Filter.CheckBox box && Boolean.TRUE.equals(box.state())) {
                String id = data.idOf(kind, box.name());
                if (id != null) {
                    out.add(id);
                }
            }
        }
    }

    /** Typesense ANDs repeated includes: {@code field:=`a` && field:=`b`}. */
    private static String joinIncludes(List<String> ids, String field) {
        List<String> parts = new ArrayList<>(ids.size());
        for (String id : ids) {
            parts.add(field + ":=`" + id + "`");
        }
        return String.join(" && ", parts);
    }

    private static String backtickList(List<String> ids) {
        List<String> parts = new ArrayList<>(ids.size());
        for (String id : ids) {
            parts.add("`" + id + "`");
        }
        return String.join(",", parts);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        JsonNode page = getJson(BASE_URL + "/api/manga/page?id=" + enc(seriesExternalId));
        if (page == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        return toResult(page.path("mangaPage"));
    }

    private List<SearchResult> mapItems(JsonNode items) {
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode item : items) {
            results.add(toResult(item));
        }
        return results;
    }

    /** Maps one manga object (browse item, Typesense document, or details page) to a {@link SearchResult}. */
    private SearchResult toResult(JsonNode manga) {
        String mangaId = text(manga.get("id"));
        String title = text(manga.get("title"));

        StringBuilder description = new StringBuilder();
        double rating = manga.path("avgRating").asDouble(0);
        if (rating > 0) {
            appendBlock(description, String.format(Locale.ENGLISH, "Rating: %.2f/10", rating));
        }
        long released = manga.path("released").asLong(0);
        if (released > 0) {
            appendBlock(description, "Year: " + Instant.ofEpochMilli(released).atZone(ZoneOffset.UTC).getYear());
        }
        // "views" is a number on the API and a preformatted string in the Typesense index.
        String views = text(manga.get("views"));
        if (views != null && !views.isBlank()) {
            appendBlock(description, "Views: " + views);
        }
        String synopsis = text(manga.get("synopsis"));
        if (synopsis != null && !synopsis.isBlank()) {
            appendBlock(description, "Synopsis: " + synopsis);
        }
        List<String> otherNames = new ArrayList<>();
        for (JsonNode name : manga.path("otherNames")) {
            String value = text(name);
            if (value != null && !value.equals(title)) {
                otherNames.add("- " + value);
            }
        }
        if (!otherNames.isEmpty()) {
            appendBlock(description, "Alternative Names:\n" + String.join("\n", otherNames));
        }

        List<String> genres = new ArrayList<>();
        String type = text(manga.get("type"));
        if (type != null) {
            genres.add(type);
        }
        // The API calls the field "genres"; the Typesense document calls it "tags".
        genres.addAll(names(manga.has("genres") ? manga.path("genres") : manga.path("tags")));

        List<String> authors = new ArrayList<>();
        List<String> artists = new ArrayList<>();
        for (JsonNode entry : manga.path("authors")) {
            if (entry.isValueNode()) {
                authors.add(entry.asString());
                continue;
            }
            String name = text(entry.get("name"));
            if (name == null) {
                continue;
            }
            String role = text(entry.get("type"));
            if ("Artist".equals(role)) {
                artists.add(name);
            } else if (role == null || "Author".equals(role)) {
                authors.add(name);
            }
        }

        JsonNode poster = manga.has("poster") ? manga.path("poster") : manga.path("image");
        return new SearchResult(id(), mangaId, title != null ? title : mangaId,
            description.isEmpty() ? null : description.toString(),
            coverUrl(manga, poster),
            authors.isEmpty() ? null : String.join(", ", authors),
            artists.isEmpty() ? null : String.join(", ", artists),
            genres, parseStatus(text(manga.get("status"))), Map.of());
    }

    private static void appendBlock(StringBuilder sb, String block) {
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(block);
    }

    /** Genre/tag arrays hold either bare strings or {@code {name}} objects. */
    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            String value = item.isValueNode() ? item.asString() : text(item.get("name"));
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    private static SeriesStatus parseStatus(String status) {
        if (status == null) {
            return SeriesStatus.UNKNOWN;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "ongoing" -> SeriesStatus.ONGOING;
            case "completed" -> SeriesStatus.COMPLETED;
            case "hiatus" -> SeriesStatus.ON_HIATUS;
            case "canceled" -> SeriesStatus.CANCELLED;
            default -> SeriesStatus.UNKNOWN;
        };
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        JsonNode all = getJson(BASE_URL + "/api/manga/allChapters?mangaId=" + enc(seriesExternalId));
        if (all == null) {
            return List.of();
        }
        // Chapters carry a scanlation-group id; the group names only live on the details page.
        Map<String, String> scanlators = new HashMap<>();
        JsonNode page = getJson(BASE_URL + "/api/manga/page?id=" + enc(seriesExternalId));
        if (page != null) {
            for (JsonNode group : page.path("mangaPage").path("scanlators")) {
                String groupId = text(group.get("id"));
                String name = text(group.get("name"));
                if (groupId != null && name != null) {
                    scanlators.put(groupId, name);
                }
            }
        }

        List<SourceChapter> chapters = new ArrayList<>();
        for (JsonNode chapter : all.path("chapters")) {
            String chapterId = text(chapter.get("id"));
            if (chapterId == null) {
                continue;
            }
            Double number = chapter.hasNonNull("number") ? chapter.path("number").asDouble() : null;
            String scanlator = scanlators.get(text(chapter.get("scanlationMangaId")));
            chapters.add(new SourceChapter(
                seriesExternalId + "/" + chapterId,
                text(chapter.get("title")),
                number,
                scanlator,
                parseDate(chapter.get("createdAt")),
                Map.of()));
        }
        // Upstream ordering: highest chapter first, grouped by scanlator, newest upload first.
        chapters.sort(Comparator
            .comparing(SourceChapter::number, Comparator.nullsLast(Comparator.<Double>reverseOrder()))
            .thenComparing(SourceChapter::scanlator, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
            .thenComparing(SourceChapter::releaseDate, Comparator.nullsLast(Comparator.<LocalDate>reverseOrder())));
        return chapters;
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        int slash = chapterExternalId.lastIndexOf('/');
        if (slash < 0) {
            return List.of();
        }
        String mangaId = chapterExternalId.substring(0, slash);
        String chapterId = chapterExternalId.substring(slash + 1);

        HttpUrl url = HttpUrl.get(BASE_URL + "/api/read/chapter").newBuilder()
            .addQueryParameter("mangaId", mangaId)
            .addQueryParameter("chapterId", chapterId)
            .build();
        JsonNode root = getJson(url.toString());
        if (root == null) {
            return List.of();
        }
        // Mirrors the extension's imageRequest(): the CDN wants an image Accept header.
        Map<String, String> headers = Map.of(
            "User-Agent", USER_AGENT,
            "Referer", BASE_URL + "/",
            "Accept", "image/avif,image/webp,*/*");
        List<SourcePage> pages = new ArrayList<>();
        int index = 0;
        for (JsonNode page : root.path("readChapter").path("pages")) {
            String image = absoluteImage(text(page.get("image")));
            if (image != null) {
                pages.add(new SourcePage(index++, image, headers));
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
     * Covers arrive as a bare string or as a {@code {largeImage, image}} object, and may be site-relative.
     * {@code largeImage} wins where present: the plain {@code image} is a small, blurry thumbnail.
     */
    private static String coverUrl(JsonNode manga, JsonNode poster) {
        String large = text(manga.get("largeImage"));
        if (large == null && poster != null && poster.isObject()) {
            large = text(poster.get("largeImage"));
        }
        if (large != null) {
            return absoluteImage(stripStaticPrefix(large));
        }
        if (poster == null || poster.isMissingNode() || poster.isNull()) {
            return null;
        }
        String path = poster.isValueNode() ? poster.asString() : text(poster.get("image"));
        return path == null ? null : absoluteImage(stripStaticPrefix(path));
    }

    private static String stripStaticPrefix(String path) {
        String out = path.startsWith("/") ? path.substring(1) : path;
        return out.startsWith("static/") ? out.substring("static/".length()) : out;
    }

    /**
     * Resolves an image reference to an absolute https URL: already-absolute urls pass through,
     * protocol-relative ones gain a scheme, and everything else is a {@code /static} path. Upstream
     * also rewrites {@code http://} and the scheme-less {@code //} form to https.
     */
    private static String absoluteImage(String image) {
        if (image == null || image.isBlank()) {
            return null;
        }
        String url;
        if (image.startsWith("http")) {
            url = image;
        } else if (image.startsWith("//")) {
            url = "https:" + image;
        } else {
            url = BASE_URL + "/static/" + stripStaticPrefix(image);
        }
        return url.replaceFirst("^https?:?//", "https://");
    }

    private static String adultParam(ProviderSettings settings) {
        return is18Enabled(settings) ? "&adult=1" : "";
    }

    private static boolean is18Enabled(ProviderSettings settings) {
        return settings != null && settings.getBoolean(PREF_SHOW_18, false);
    }

    private JsonNode getJson(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .header("Accept", "*/*")
            .header("Content-Type", "application/json")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            if (!res.isSuccessful() || payload == null) {
                int code = res.code();
                LOG.log(System.Logger.Level.WARNING, () -> "Atsumaru HTTP " + code + " for " + url);
                return null;
            }
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Atsumaru request failed for " + url, e);
            return null; // fail soft, per the SPI contract
        }
    }

    /** A scalar JSON node's text, or null when the field is absent/null/non-scalar. */
    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asString() : null;
    }

    private static Integer parseInt(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code createdAt} is either epoch millis or an ISO-8601 instant. */
    private static LocalDate parseDate(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return Instant.ofEpochMilli(node.asLong()).atZone(ZoneOffset.UTC).toLocalDate();
        }
        String value = text(node);
        try {
            return value != null && value.length() >= 10 ? LocalDate.parse(value.substring(0, 10)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
