package dev.kodex.ext.vortexscans;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.content.ContentSource;
import dev.kodex.spi.content.SearchResult;
import dev.kodex.spi.content.SeriesPage;
import dev.kodex.spi.content.SeriesStatus;
import dev.kodex.spi.content.SourceChapter;
import dev.kodex.spi.content.SourcePage;
import dev.kodex.spi.content.filter.FilterList;
import dev.kodex.spi.common.http.HttpClientProvider;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.pf4j.Extension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Extension
public class VortexScansSource implements ContentSource {

    private static final String SITE = "https://vortexscans.org";
    private static final String API = "https://api.vortexscans.org";
    private static final int PER_PAGE = 18;
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    public String displayName() {
        return "Vortex Scans";
    }

    @Override
    public String website() {
        return SITE;
    }

    @Override
    public String language() {
        return "en";
    }

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList();
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return query(page, "", "totalViews", "desc", null);
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return query(page, "", "lastChapterAddedAt", "desc", null);
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        FilterList effective = (filters == null || filters.filters().isEmpty()) ? getFilterList() : filters;
        return query(page, query == null ? "" : query.trim(), null, null, effective);
    }

    private SeriesPage query(int page, String searchTerm, String orderBy, String orderDirection, FilterList filters) {
        int p = Math.max(1, page);
        HttpUrl.Builder url = HttpUrl.get(API + "/api/query").newBuilder()
            .addQueryParameter("page", String.valueOf(p))
            .addQueryParameter("perPage", String.valueOf(PER_PAGE))
            .addQueryParameter("searchTerm", searchTerm);
        if (filters != null) {
            Filters.applyToUrl(url, filters);
        }
        if (orderBy != null) {
            url.setQueryParameter("orderBy", orderBy);
        }
        if (orderDirection != null) {
            url.setQueryParameter("orderDirection", orderDirection);
        }
        JsonNode root = getJson(url.build().toString());
        if (root == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        for (JsonNode post : root.path("posts")) {
            items.add(summary(post));
        }
        boolean hasNext = root.path("totalCount").asInt(0) > p * PER_PAGE;
        return new SeriesPage(items, hasNext);
    }

    private SearchResult summary(JsonNode post) {
        String slug = post.path("slug").asText("");
        String externalId = slug + "#" + post.path("id").asInt();
        List<String> genres = new ArrayList<>();
        for (JsonNode g : post.path("genres")) {
            String name = g.path("name").asText(null);
            if (name != null) {
                genres.add(name);
            }
        }
        return new SearchResult(id(), externalId, post.path("postTitle").asText(externalId).trim(),
            null, nullable(post.path("featuredImage")), null, null, genres,
            parseStatus(post.path("seriesStatus").asText(null)), Map.of());
    }

    private static SeriesStatus parseStatus(String status) {
        if (status == null) {
            return SeriesStatus.UNKNOWN;
        }
        return switch (status) {
            case "ONGOING", "COMING_SOON", "MASS_RELEASED" -> SeriesStatus.ONGOING;
            case "COMPLETED" -> SeriesStatus.COMPLETED;
            case "CANCELLED", "DROPPED" -> SeriesStatus.CANCELLED;
            default -> SeriesStatus.UNKNOWN;
        };
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        int postId = idPart(seriesExternalId);
        JsonNode root = postId > 0 ? getJson(API + "/api/post?postId=" + postId) : null;
        if (root == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        JsonNode post = root.path("post");
        String slug = post.path("slug").asText(slugPart(seriesExternalId));
        String externalId = slug + "#" + post.path("id").asInt(postId);
        List<String> genres = new ArrayList<>();
        String seriesType = post.path("seriesType").asText(null);
        if (seriesType != null) {
            switch (seriesType.toUpperCase()) {
                case "MANGA" -> genres.add("Manga");
                case "MANHUA" -> genres.add("Manhua");
                case "MANHWA" -> genres.add("Manhwa");
                default -> {
                }
            }
        }
        for (JsonNode g : post.path("genres")) {
            String name = g.path("name").asText(null);
            if (name != null && !genres.contains(name)) {
                genres.add(name);
            }
        }
        return new SearchResult(id(), externalId, post.path("postTitle").asText(externalId).trim(),
            description(post), nullable(post.path("featuredImage")),
            blankToNull(post.path("author").asText("")), blankToNull(post.path("artist").asText("")),
            genres, parseStatus(post.path("seriesStatus").asText(null)), Map.of());
    }

    private static String description(JsonNode post) {
        StringBuilder sb = new StringBuilder();
        String content = post.path("postContent").asText("");
        if (!content.isBlank()) {
            String text = Jsoup.parse(content.replaceAll("(?i)<br\\s*/?>", "\n")).text().trim();
            sb.append(text);
        }
        String alt = post.path("alternativeTitles").asText("");
        if (!alt.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("Alternative titles:\n").append(alt.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        int postId = idPart(seriesExternalId);
        List<SourceChapter> chapters = new ArrayList<>();
        JsonNode root = postId > 0 ? getJson(API + "/api/post?postId=" + postId) : null;
        if (root == null) {
            return chapters;
        }
        JsonNode post = root.path("post");
        String mangaSlug = post.path("slug").asText(slugPart(seriesExternalId));
        for (JsonNode ch : post.path("chapters")) {
            boolean accessible = !ch.path("isAccessible").isBoolean() || ch.path("isAccessible").asBoolean();
            boolean locked = ch.path("isLocked").asBoolean(false);
            String chapterSlug = ch.path("slug").asText("");
            String externalId = mangaSlug + "/" + chapterSlug + "#" + ch.path("id").asInt();
            String numberText = ch.path("number").asText("").trim();
            if (numberText.isEmpty()) {
                numberText = afterPrefix(chapterSlug, "chapter-");
            }
            StringBuilder name = new StringBuilder();
            if (!accessible || locked) {
                name.append("🔒 ");
            }
            name.append("Chapter");
            if (!numberText.isEmpty()) {
                name.append(' ').append(numberText);
            }
            String title = ch.path("title").asText("").trim();
            if (!title.isEmpty()) {
                name.append(" - ").append(title);
            }
            chapters.add(new SourceChapter(externalId, name.toString(),
                parseDouble(numberText), null, parseDate(ch.path("createdAt").asText(null)), Map.of()));
        }
        return chapters;
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        int chapterId = idPart(chapterExternalId);
        List<SourcePage> pages = new ArrayList<>();
        JsonNode root = chapterId > 0 ? getJson(API + "/api/chapter?chapterId=" + chapterId) : null;
        if (root == null) {
            return pages;
        }
        JsonNode chapter = root.path("chapter");
        Map<String, String> headers = Map.of("Referer", SITE + "/", "User-Agent", USER_AGENT);
        List<JsonNode> images = new ArrayList<>();
        chapter.path("images").forEach(images::add);
        images.sort(Comparator.comparingInt(n -> n.path("order").asInt(Integer.MAX_VALUE)));
        for (int i = 0; i < images.size(); i++) {
            String src = nullable(images.get(i).path("url"));
            if (src != null && !src.isBlank()) {
                pages.add(new SourcePage(i, src, headers));
            }
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", SITE + "/");
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private JsonNode getJson(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", SITE + "/")
            .header("Origin", SITE)
            .header("Accept", "application/json, text/plain, */*")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                return null;
            }
            return MAPPER.readTree(body.string());
        } catch (Exception e) {
            return null; // fail soft, per the SPI contract
        }
    }

    /** The trailing {@code #id} of an external id, or {@code -1} if absent/non-numeric. */
    private static int idPart(String externalId) {
        int hash = externalId.lastIndexOf('#');
        if (hash < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(externalId.substring(hash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String slugPart(String externalId) {
        int hash = externalId.indexOf('#');
        String slug = hash < 0 ? externalId : externalId.substring(0, hash);
        int slash = slug.indexOf('/');
        return slash < 0 ? slug : slug.substring(0, slash);
    }

    private static String afterPrefix(String s, String prefix) {
        int i = s.indexOf(prefix);
        return i < 0 ? "" : s.substring(i + prefix.length());
    }

    private static String nullable(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asText() : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Double parseDouble(String s) {
        try {
            return s == null || s.isBlank() ? null : Double.parseDouble(s);
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
}
