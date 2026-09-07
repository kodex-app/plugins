package dev.kodex.plugin.nhentai;

import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.common.http.ProviderRateLimitException;
import dev.kodex.spi.content.ContentSource;
import dev.kodex.spi.content.SearchResult;
import dev.kodex.spi.content.SeriesPage;
import dev.kodex.spi.content.SeriesStatus;
import dev.kodex.spi.content.SourceChapter;
import dev.kodex.spi.content.SourcePage;
import dev.kodex.spi.content.filter.FilterList;
import dev.kodex.spi.common.http.SourceUnavailableException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.pf4j.Extension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * nhentai — a doujinshi gallery site whose whole catalogue is served by a JSON API. Series ids are
 * the site's own gallery paths ({@code /g/<id>/}), which is also what Mihon stores, so backup
 * imports need no translation.
 *
 * <p>nhentai serves many languages, so — like Mihon (and the {@code kagane}/{@code hentaifox}
 * plugins here) — it is exposed as one <b>per-language source per {@code @Extension}</b> rather than
 * a single mixed-language one: {@link English}, {@link Japanese}, {@link Chinese}. Each narrows
 * every browse/search to its own {@link #mangaLang() language:} slug and reports its own BCP-47
 * {@link #language()}, so each gets its own Mihon-aligned {@link #id()} and shows up as a distinct
 * entry in Mihon's "All" extension list.
 *
 * <p>Speaks the <b>v2</b> API ({@code /api/v2}, documented at {@code /api/v2/docs}): the v1
 * endpoints the Mihon extension used now answer {@code 403 "Use new API"}. v2 also stopped hardcoding
 * image hosts — a gallery reports bare paths, and {@code /api/v2/cdn} names the servers they hang off.
 */
public abstract class NHentaiSource implements ContentSource {

    private static final String BASE_URL = "https://nhentai.net";
    private static final String API_URL = BASE_URL + "/api/v2";
    // Used only if /api/v2/cdn can't be read; the numbered hosts it lists are interchangeable mirrors.
    private static final List<String> FALLBACK_IMAGE_SERVERS = List.of("https://i1.nhentai.net");
    private static final List<String> FALLBACK_THUMB_SERVERS = List.of("https://t1.nhentai.net");
    // Cloudflare fronts nhentai and rejects non-browser clients; this UA + Referer pair goes on every request.
    private static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;
    /** CDN hosts from {@code /api/v2/cdn}, fetched once per source instance. */
    private volatile List<String> imageServers;
    private volatile List<String> thumbServers;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.httpClient() : FALLBACK;
    }

    /** The nhentai {@code language:} slug this source narrows to ({@code english}/{@code japanese}/{@code chinese}). */
    protected abstract String mangaLang();

    /** This source's BCP-47 language tag ({@code en}/{@code ja}/{@code zh}) — drives {@link #id()}. */
    @Override
    public abstract String language();

    @Override
    public String displayName() {
        return "NHentai";
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
    public FilterList getFilterList() {
        return Filters.defaultFilterList();
    }

    // ---- Browse / search -------------------------------------------------------------------------

    // Every feed goes through /search so the source's language: slug can narrow it; the all-language
    // /galleries and /galleries/popular endpoints can't be scoped to a single language.

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return searchGalleries(withLanguage(Filters.MATCH_ALL), "popular", page);
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return searchGalleries(withLanguage(Filters.MATCH_ALL), "date", page);
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        FilterList effective = (filters == null || filters.filters().isEmpty()) ? getFilterList() : filters;
        return searchGalleries(withLanguage(Filters.combineQuery(query, effective)), Filters.sort(effective), page);
    }

    private SeriesPage searchGalleries(String query, String sort, int page) {
        HttpUrl.Builder url = HttpUrl.get(API_URL + "/search").newBuilder()
            // `query` is mandatory and must be non-empty — withLanguage always yields at least language:<slug>.
            .addQueryParameter("query", query);
        if (sort != null) {
            url.addQueryParameter("sort", sort);
        }
        url.addQueryParameter("page", String.valueOf(Math.max(1, page)));
        return galleryList(url.build().toString());
    }

    /** Appends this source's {@code language:<slug>} token, replacing the bare match-all query outright. */
    private String withLanguage(String query) {
        String tag = "language:" + mangaLang();
        if (query == null || query.isBlank() || query.equals(Filters.MATCH_ALL)) {
            return tag;
        }
        return query + " " + tag;
    }

    /** Reads a {@code PaginatedResponse[GalleryListItem]} — the shape both /search and /galleries return. */
    private SeriesPage galleryList(String url) {
        JsonNode root = getJson(url);
        List<SearchResult> items = new ArrayList<>();
        for (JsonNode gallery : root.path("result")) {
            items.add(fromListItem(gallery));
        }
        // v2 reports the page count outright, so no guessing from a full page of results.
        int page = pageOf(url);
        return new SeriesPage(items, page < root.path("num_pages").asInt(page));
    }

    /**
     * A list entry: v2 trims these to titles and a thumbnail (tags arrive as bare
     * {@code tag_ids}), so everything else is left to {@link #seriesDetails}.
     */
    private SearchResult fromListItem(JsonNode gallery) {
        String title = firstNonBlank(text(gallery, "english_title"), text(gallery, "japanese_title"));
        return new SearchResult(id(), externalIdOf(gallery), title.isBlank() ? externalIdOf(gallery) : title,
            null, thumbUrl(text(gallery, "thumbnail")), null, null,
            List.of(), SeriesStatus.COMPLETED, Map.of());
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        JsonNode gallery = getJson(API_URL + "/galleries/" + galleryId(seriesExternalId));
        if (gallery.path("id").isMissingNode()) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        JsonNode title = gallery.path("title");
        String display = firstNonBlank(text(title, "english"), text(title, "pretty"), text(title, "japanese"));

        Map<String, List<String>> tagsByType = tagsByType(gallery.path("tags"));
        String artists = join(tagsByType.get("artist"));

        StringBuilder description = new StringBuilder();
        appendTags(description, "Parody", tagsByType.get("parody"));
        appendTags(description, "Characters", tagsByType.get("character"));
        appendTags(description, "Group", tagsByType.get("group"));
        description.append("Pages: ").append(gallery.path("num_pages").asInt(0)).append('\n');
        description.append("Favorites: ").append(gallery.path("num_favorites").asInt(0));

        String externalId = externalIdOf(gallery);
        return new SearchResult(id(), externalId, display.isBlank() ? externalId : display,
            description.toString(), thumbUrl(text(gallery.path("cover"), "path")),
            artists, artists, tagsByType.getOrDefault("tag", List.of()),
            // Doujinshi are one-shots: there is never anything more to publish.
            SeriesStatus.COMPLETED, Map.of());
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        String galleryId = galleryId(seriesExternalId);
        JsonNode gallery = getJson(API_URL + "/galleries/" + galleryId);
        if (gallery.path("id").isMissingNode()) {
            return List.of();
        }
        // An nhentai gallery is a single readable unit — the gallery itself is the one "chapter".
        return List.of(new SourceChapter("/g/" + galleryId + "/", "Chapter 1", 1.0, null,
            uploadDate(gallery.path("upload_date").asLong(0L)), Map.of()));
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        JsonNode gallery = getJson(API_URL + "/galleries/" + galleryId(chapterExternalId));
        // Pages are served off any image mirror; pin one per gallery so a reader keeps a single connection.
        String server = pick(imageServers(), gallery.path("media_id").asInt(0));
        Map<String, String> headers = imageHeaders();
        List<SourcePage> pages = new ArrayList<>();
        int index = 0;
        for (JsonNode page : gallery.path("pages")) {
            String path = text(page, "path");
            if (!path.isBlank()) {
                pages.add(new SourcePage(index++, server + "/" + path, headers));
            }
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return imageHeaders();
    }

    // ---- CDN -------------------------------------------------------------------------------------

    /**
     * The image/thumbnail hosts from {@code /api/v2/cdn}. v2 galleries carry only paths
     * ({@code galleries/<media_id>/1.webp}), and the servers are the site's own answer for what to
     * hang them off — so they're read from there rather than hardcoded.
     */
    private List<String> imageServers() {
        if (imageServers == null) {
            loadCdnConfig();
        }
        return imageServers;
    }

    private List<String> thumbServers() {
        if (thumbServers == null) {
            loadCdnConfig();
        }
        return thumbServers;
    }

    private synchronized void loadCdnConfig() {
        if (imageServers != null && thumbServers != null) {
            return;
        }
        JsonNode cdn = getJsonOrNull(API_URL + "/cdn");
        imageServers = hosts(cdn == null ? null : cdn.path("image_servers"), FALLBACK_IMAGE_SERVERS);
        thumbServers = hosts(cdn == null ? null : cdn.path("thumb_servers"), FALLBACK_THUMB_SERVERS);
    }

    private static List<String> hosts(JsonNode array, List<String> fallback) {
        if (array == null || !array.isArray()) {
            return fallback;
        }
        List<String> hosts = new ArrayList<>();
        for (JsonNode host : array) {
            String value = host.asString("");
            if (!value.isBlank()) {
                hosts.add(value.replaceAll("/+$", ""));
            }
        }
        return hosts.isEmpty() ? fallback : hosts;
    }

    /** Covers and thumbnails live on the thumb mirrors, not the page-image ones. */
    private String thumbUrl(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return pick(thumbServers(), path.hashCode()) + "/" + path;
    }

    /** Spreads galleries across the mirrors, but deterministically — the same gallery keeps one host. */
    private static String pick(List<String> servers, int key) {
        return servers.get(Math.floorMod(key, servers.size()));
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private static Map<String, String> imageHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
    }

    /** The gallery path Kodex and Mihon both store: {@code /g/<id>/}. */
    private static String externalIdOf(JsonNode gallery) {
        return "/g/" + gallery.path("id").asInt(0) + "/";
    }

    /** {@code /g/123456/} → {@code 123456}. */
    private static String galleryId(String externalId) {
        if (externalId == null) {
            return "";
        }
        String trimmed = externalId.replaceAll("/+$", "");
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    /** Groups the gallery's tags by their {@code type} (tag, artist, character, parody, group, …). */
    private static Map<String, List<String>> tagsByType(JsonNode tags) {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (JsonNode tag : tags) {
            String name = text(tag, "name");
            if (!name.isBlank()) {
                byType.computeIfAbsent(text(tag, "type"), k -> new ArrayList<>()).add(name);
            }
        }
        return byType;
    }

    private static void appendTags(StringBuilder out, String label, List<String> values) {
        if (values != null && !values.isEmpty()) {
            out.append(label).append(": ").append(join(values)).append('\n');
        }
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }

    /** A string field, with explicit JSON {@code null}s (v2 marks optional titles that way) read as blank. */
    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return node.isNull() || node.isMissingNode() ? "" : node.asString("");
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private static int pageOf(String url) {
        String page = HttpUrl.get(url).queryParameter("page");
        try {
            return page == null ? 1 : Integer.parseInt(page);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static LocalDate uploadDate(long epochSeconds) {
        return epochSeconds <= 0 ? null : Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Calls the API. A failed request throws instead of returning null: a block or an outage used to
     * come back as an empty feed, which the apps can only render as "this source has nothing".
     */
    private JsonNode getJson(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .header("Accept", "application/json")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            throwIfRateLimited(res);
            ResponseBody body = res.body();
            String payload = body == null ? null : body.string();
            if (!res.isSuccessful() || payload == null) {
                throw SourceUnavailableException.http(displayName(), url, res.code(), payload);
            }
            return MAPPER.readTree(payload);
        } catch (RuntimeException e) {
            throw e; // rate limit must reach the core's retry/backoff handling; unavailable is already right
        } catch (Exception e) {
            throw SourceUnavailableException.transport(displayName(), url, e);
        }
    }

    /**
     * {@link #getJson} for a call that has a real fallback — the CDN host list, which has built-in
     * defaults. Only those may swallow a failure; everything else must surface it.
     */
    private JsonNode getJsonOrNull(String url) {
        try {
            return getJson(url);
        } catch (SourceUnavailableException e) {
            return null;
        }
    }

    /**
     * Signals a 429 to the core so the download worker backs off and retries instead of failing the
     * chapter. v2 rate-limits anonymous callers hard (10 searches/min, 20 gallery reads/min), so this
     * is a normal outcome of a busy library scan, not an error.
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
        throw new ProviderRateLimitException("nhentai rate limit (HTTP 429)", retryAfter);
    }

    // ---- Per-language sources (one @Extension each, like Mihon's NHentaiFactory) -----------------

    /** nhentai's English galleries ({@code language:english}). */
    @Extension
    public static final class English extends NHentaiSource {
        @Override
        protected String mangaLang() {
            return "english";
        }

        @Override
        public String language() {
            return "en";
        }
    }

    /** nhentai's Japanese galleries ({@code language:japanese}). */
    @Extension
    public static final class Japanese extends NHentaiSource {
        @Override
        protected String mangaLang() {
            return "japanese";
        }

        @Override
        public String language() {
            return "ja";
        }
    }

    /** nhentai's Chinese galleries ({@code language:chinese}). */
    @Extension
    public static final class Chinese extends NHentaiSource {
        @Override
        protected String mangaLang() {
            return "chinese";
        }

        @Override
        public String language() {
            return "zh";
        }
    }
}
