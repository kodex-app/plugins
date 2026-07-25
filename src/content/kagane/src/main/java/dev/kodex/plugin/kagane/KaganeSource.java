package dev.kodex.plugin.kagane;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
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
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.pf4j.Extension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public abstract class KaganeSource implements ContentSource {

    private static final String DOMAIN = "kagane.to";
    private static final String BASE_URL = "https://" + DOMAIN;
    private static final String API_URL = "https://yuzuki." + DOMAIN;
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    // API variant names are capitalized (the JSON enum rejects lowercase).
    private static final List<String> CONTENT_RATINGS = List.of("Safe", "Suggestive", "Erotica", "Pornographic");
    private static final int PAGE_SIZE = 35;

    private final String lang;
    private final List<String> contentLangs;

    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    private volatile String accessToken = "";
    private volatile String cacheUrl = "https://akari." + DOMAIN;
    private volatile String integrityToken = "";
    private volatile long integrityExp = 0L;

    protected KaganeSource(String lang, List<String> contentLangs) {
        this.lang = lang;
        this.contentLangs = List.copyOf(contentLangs);
    }

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
        return "Kagane";
    }

    @Override
    public boolean adultContent() {
        return true;
    }

    @Override
    public String website() {
        return BASE_URL;
    }

    @Override
    public String language() {
        return lang;
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return runSearch(page, null, "total_views,desc", null);
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return runSearch(page, null, "updated_at,desc", null);
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        return runSearch(page, query == null || query.isBlank() ? null : query.trim(),
            sortParam(filters), filters);
    }

    private SeriesPage runSearch(int page, String title, String sort, FilterList filters) {
        ObjectNode body = MAPPER.createObjectNode();
        if (title != null) {
            body.put("title", title);
        }
        ArrayNode sourceType = body.putArray("source_type");
        sourceType.add("Official");
        sourceType.add("Unofficial");
        sourceType.add("Mixed");

        putStringArray(body, "content_rating", selectedMulti(filters, Filters.CONTENT_RATING, CONTENT_RATINGS));
        putStringArray(body, "format", selectedMulti(filters, Filters.FORMAT, List.of()));
        putStringArray(body, "upload_status", selectedStatuses(filters));

        ArrayNode langs = body.putArray("content_lang");
        contentLangs.forEach(langs::add);

        HttpUrl.Builder url = HttpUrl.get(API_URL + "/api/v2/search/series").newBuilder()
            .addQueryParameter("page", String.valueOf(Math.max(0, page - 1)))
            .addQueryParameter("size", String.valueOf(PAGE_SIZE));
        if (sort != null && !sort.isEmpty()) {
            url.addQueryParameter("sort", sort);
        }

        JsonNode dto = postJson(url.build().toString(), body.toString());
        if (dto == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        for (JsonNode book : dto.path("content")) {
            String seriesId = text(book, "series_id");
            if (seriesId == null) {
                continue;
            }
            items.add(new SearchResult(id(), seriesId,
                trim(text(book, "title")), null, imageUrl(text(book, "cover_image_id")),
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        boolean hasNextPage = !dto.path("last").asBoolean(true);
        return new SeriesPage(items, hasNextPage);
    }

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList(CONTENT_RATINGS);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        JsonNode dto = getJson(API_URL + "/api/v2/series/" + seriesExternalId, true);
        if (dto == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        String title = trim(text(dto, "title"));

        String thumbnail = null;
        JsonNode covers = dto.path("series_covers");
        if (covers.isArray() && !covers.isEmpty()) {
            thumbnail = imageUrl(text(covers.get(0), "image_id"));
        }

        // Authors / artists from staff roles.
        Set<String> authors = new LinkedHashSet<>();
        Set<String> artists = new LinkedHashSet<>();
        for (JsonNode staff : dto.path("series_staff")) {
            String role = text(staff, "role");
            String person = text(staff, "name");
            if (role == null || person == null) {
                continue;
            }
            String r = role.toLowerCase(Locale.ROOT);
            if (r.contains("author") || r.contains("story")) {
                authors.add(person);
            }
            if (r.contains("artist") || r.contains("art")) {
                artists.add(person);
            }
        }

        Set<String> genres = new LinkedHashSet<>();
        String format = text(dto, "format");
        if (format != null && !format.isBlank()) {
            genres.add(format);
        }
        for (JsonNode genre : dto.path("genres")) {
            String g = text(genre, "genre_name");
            if (g != null) {
                genres.add(g);
            }
        }

        StringBuilder desc = new StringBuilder();
        String description = text(dto, "description");
        if (description != null && !description.isBlank()) {
            desc.append(description.trim());
        }
        JsonNode altTitles = dto.path("series_alternate_titles");
        if (altTitles.isArray() && !altTitles.isEmpty()) {
            if (desc.length() > 0) {
                desc.append("\n\n");
            }
            desc.append("Associated Name(s):");
            for (JsonNode alt : altTitles) {
                String t = text(alt, "title");
                if (t != null) {
                    desc.append("\n• ").append(t);
                }
            }
        }

        return new SearchResult(id(), seriesExternalId, title,
            desc.length() == 0 ? null : desc.toString(), thumbnail,
            authors.isEmpty() ? null : String.join(", ", authors),
            artists.isEmpty() ? null : String.join(", ", artists),
            new ArrayList<>(genres), parseStatus(text(dto, "upload_status")), Map.of());
    }

    private static SeriesStatus parseStatus(String status) {
        if (status == null) {
            return SeriesStatus.UNKNOWN;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "ONGOING" -> SeriesStatus.ONGOING;
            case "COMPLETED" -> SeriesStatus.COMPLETED;
            case "HIATUS" -> SeriesStatus.ON_HIATUS;
            case "ABANDONED" -> SeriesStatus.CANCELLED;
            default -> SeriesStatus.UNKNOWN;
        };
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        JsonNode dto = getJson(API_URL + "/api/v2/series/" + seriesExternalId, true);
        List<SourceChapter> chapters = new ArrayList<>();
        if (dto == null) {
            return chapters;
        }
        String format = text(dto, "format");
        boolean useSourceNumber = format != null && Set.of(
            "Dark Horse Comics", "Flame Comics", "MangaDex", "Square Enix Manga").contains(format);

        for (JsonNode book : dto.path("series_books")) {
            String bookId = text(book, "book_id");
            if (bookId == null) {
                continue;
            }
            String title = text(book, "title");
            String chapterNo = text(book, "chapter_no");
            String name = title != null && !title.isBlank() ? title.trim()
                : (chapterNo != null && !chapterNo.isBlank() ? "Ch." + chapterNo : "Chapter");

            Double number = null;
            if (useSourceNumber && book.path("sort_no").isNumber()) {
                number = book.path("sort_no").asDouble();
            } else if (chapterNo != null) {
                number = parseDouble(chapterNo);
            }

            List<String> groups = new ArrayList<>();
            for (JsonNode g : book.path("groups")) {
                String gt = text(g, "title");
                if (gt != null) {
                    groups.add(gt);
                }
            }

            chapters.add(new SourceChapter(bookId, name, number,
                groups.isEmpty() ? null : String.join(", ", groups),
                parseDate(text(book, "created_at")), Map.of()));
        }
        // API returns oldest-first; present newest-first like the upstream.
        Collections.reverse(chapters);
        return chapters;
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        List<SourcePage> pages = new ArrayList<>();
        JsonNode challenge = fetchChallenge(chapterExternalId);
        if (challenge == null) {
            return pages;
        }
        accessToken = text(challenge, "access_token");
        String cache = text(challenge, "cache_url");
        if (cache != null) {
            cacheUrl = cache;
        }
        Map<String, String> headers = Map.of(
            "User-Agent", USER_AGENT, "Origin", BASE_URL, "Referer", BASE_URL + "/");
        for (JsonNode page : challenge.path("manifest").path("pages")) {
            String pageId = text(page, "page_id");
            if (pageId == null) {
                continue;
            }
            String ext = text(page, "ext");
            int pageNo = page.path("page_no").asInt(pages.size());
            String url = HttpUrl.get(cacheUrl + "/api/v2/books/page").newBuilder()
                .addPathSegment(chapterExternalId)
                .addPathSegment(pageId + "." + (ext == null ? "jxl" : ext))
                .addQueryParameter("token", accessToken == null ? "" : accessToken)
                .addQueryParameter("is_datasaver", "false")
                .build().toString();
            pages.add(new SourcePage(pageNo, url, headers));
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Origin", BASE_URL, "Referer", BASE_URL + "/");
    }

    /** Solves the integrity + per-chapter challenge, returning the challenge JSON (with manifest). */
    private JsonNode fetchChallenge(String chapterId) {
        String token = integrityToken();
        if (token == null) {
            return null;
        }
        HttpUrl url = HttpUrl.get(API_URL + "/api/v2/books/" + chapterId).newBuilder()
            .addQueryParameter("is_datasaver", "false")
            .build();
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", BASE_URL)
            .header("Referer", BASE_URL + "/")
            .header("x-integrity-token", token)
            .post(RequestBody.create("{}", JSON))
            .build();
        String body = execute(req);
        if (body == null) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** Lazily fetches/caches the site integrity token (warming cookies via a homepage GET first). */
    private String integrityToken() {
        if (integrityExp >= System.currentTimeMillis() && !integrityToken.isEmpty()) {
            return integrityToken;
        }
        synchronized (this) {
            if (integrityExp >= System.currentTimeMillis() && !integrityToken.isEmpty()) {
                return integrityToken;
            }
            // Warm cookies.
            try (Response ignored = http().newCall(new Request.Builder()
                .url(BASE_URL + "/").header("User-Agent", USER_AGENT).get().build()).execute()) {
                // discard
            } catch (Exception ignored) {
                // continue regardless
            }
            Request req = new Request.Builder()
                .url(BASE_URL + "/api/integrity")
                .header("User-Agent", USER_AGENT)
                .header("Referer", BASE_URL + "/")
                .post(RequestBody.create("", JSON))
                .build();
            String body = execute(req);
            if (body == null) {
                return integrityToken.isEmpty() ? null : integrityToken;
            }
            try {
                JsonNode dto = MAPPER.readTree(body);
                String tok = text(dto, "token");
                if (tok != null) {
                    integrityToken = tok;
                    integrityExp = dto.path("exp").asLong(0L) * 1000L;
                }
            } catch (Exception ignored) {
                // keep previous token
            }
            return integrityToken.isEmpty() ? null : integrityToken;
        }
    }

    // ---- Filter helpers --------------------------------------------------------------------------

    private static String sortParam(FilterList filters) {
        if (filters != null) {
            for (Filter<?> f : filters.filters()) {
                if (f instanceof Filter.Sort sort && Filters.SORT.equals(sort.name())) {
                    Filter.Sort.Selection sel = sort.state();
                    int idx = sel != null ? sel.index() : 0;
                    String base = idx >= 0 && idx < Filters.SORT_VALUES.size() ? Filters.SORT_VALUES.get(idx) : "";
                    if (base.isEmpty()) {
                        return "";
                    }
                    return sel != null && sel.ascending() ? base : base + ",desc";
                }
            }
        }
        return "";
    }

    /** Selected option ids from a checkbox group, or {@code fallback} when the group is untouched/absent. */
    private static List<String> selectedMulti(FilterList filters, String groupName, List<String> fallback) {
        if (filters != null) {
            for (Filter<?> f : filters.filters()) {
                if (f instanceof Filter.Group group && groupName.equals(group.name())) {
                    List<String> selected = new ArrayList<>();
                    for (Filter<?> child : group.state()) {
                        if (child instanceof Filter.CheckBox cb && Boolean.TRUE.equals(cb.state())) {
                            selected.add(Filters.optionValue(groupName, cb.name()));
                        }
                    }
                    return selected.isEmpty() ? fallback : selected;
                }
            }
        }
        return fallback;
    }

    private static List<String> selectedStatuses(FilterList filters) {
        return selectedMulti(filters, Filters.STATUS, List.of());
    }

    private static void putStringArray(ObjectNode body, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ArrayNode arr = body.putArray(field);
        values.forEach(arr::add);
    }

    // ---- HTTP helpers ----------------------------------------------------------------------------

    private JsonNode getJson(String url, boolean api) {
        Request.Builder b = new Request.Builder().url(url).header("User-Agent", USER_AGENT);
        if (api) {
            b.header("Origin", BASE_URL).header("Referer", BASE_URL + "/");
        }
        String body = execute(b.get().build());
        return parse(body);
    }

    private JsonNode postJson(String url, String json) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", BASE_URL)
            .header("Referer", BASE_URL + "/")
            .header("Accept", "application/json")
            .post(RequestBody.create(json, JSON))
            .build();
        return parse(execute(req));
    }

    private String execute(Request req) {
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                return null;
            }
            return body.string();
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode parse(String body) {
        if (body == null) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String imageUrl(String imageId) {
        return imageId == null ? null : API_URL + "/api/v2/image/" + imageId;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() && v.isValueNode() ? v.asText() : null;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Concrete per-language extensions ---------------------------------

    @Extension public static final class English extends KaganeSource { public English() { super("en", List.of("en")); } }
    @Extension public static final class Japanese extends KaganeSource { public Japanese() { super("ja", List.of("ja")); } }
    @Extension public static final class Korean extends KaganeSource { public Korean() { super("ko", List.of("ko")); } }
    @Extension public static final class Chinese extends KaganeSource { public Chinese() { super("zh", List.of("zh-Hans", "zh-Hant")); } }
    @Extension public static final class Spanish extends KaganeSource { public Spanish() { super("es", List.of("es")); } }
    @Extension public static final class SpanishLatAm extends KaganeSource { public SpanishLatAm() { super("es-419", List.of("es-419")); } }
    @Extension public static final class French extends KaganeSource { public French() { super("fr", List.of("fr")); } }
    @Extension public static final class German extends KaganeSource { public German() { super("de", List.of("de")); } }
    @Extension public static final class Portuguese extends KaganeSource { public Portuguese() { super("pt", List.of("pt")); } }
    @Extension public static final class PortugueseBrazil extends KaganeSource { public PortugueseBrazil() { super("pt-BR", List.of("pt-BR")); } }
    @Extension public static final class Russian extends KaganeSource { public Russian() { super("ru", List.of("ru")); } }
    @Extension public static final class Italian extends KaganeSource { public Italian() { super("it", List.of("it")); } }
    @Extension public static final class Indonesian extends KaganeSource { public Indonesian() { super("id", List.of("id")); } }
    @Extension public static final class Vietnamese extends KaganeSource { public Vietnamese() { super("vi", List.of("vi")); } }
    @Extension public static final class Thai extends KaganeSource { public Thai() { super("th", List.of("th")); } }
    @Extension public static final class Polish extends KaganeSource { public Polish() { super("pl", List.of("pl")); } }
    @Extension public static final class Hindi extends KaganeSource { public Hindi() { super("hi", List.of("hi")); } }
    @Extension public static final class Arabic extends KaganeSource { public Arabic() { super("ar", List.of("ar")); } }
}
