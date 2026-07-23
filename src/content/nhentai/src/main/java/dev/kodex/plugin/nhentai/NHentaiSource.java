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
 */
@Extension
public class NHentaiSource implements ContentSource {

    private static final String BASE_URL = "https://nhentai.net";
    private static final String API_URL = BASE_URL + "/api";
    private static final String IMG_URL = "https://i.nhentai.net/galleries";
    private static final String THUMB_URL = "https://t.nhentai.net/galleries";
    // Cloudflare fronts nhentai and rejects non-browser clients; this UA + Referer pair goes on every request.
    private static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_PER_PAGE = 25;

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
        return "NHentai";
    }

    @Override
    public String language() {
        return "en";
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

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        HttpUrl url = HttpUrl.get(API_URL + "/galleries/search").newBuilder()
            .addQueryParameter("query", "*")
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", String.valueOf(Math.max(1, page)))
            .build();
        return galleryList(url.toString());
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        HttpUrl url = HttpUrl.get(API_URL + "/galleries/all").newBuilder()
            .addQueryParameter("page", String.valueOf(Math.max(1, page)))
            .build();
        return galleryList(url.toString());
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        FilterList effective = (filters == null || filters.filters().isEmpty()) ? getFilterList() : filters;
        HttpUrl.Builder url = HttpUrl.get(API_URL + "/galleries/search").newBuilder();
        String sort = Filters.sort(effective);
        if (sort != null) {
            url.addQueryParameter("sort", sort);
        }
        url.addQueryParameter("query", Filters.combineQuery(query, effective));
        url.addQueryParameter("page", String.valueOf(Math.max(1, page)));
        return galleryList(url.build().toString());
    }

    private SeriesPage galleryList(String url) {
        JsonNode root = getJson(url);
        if (root == null) {
            return SeriesPage.empty();
        }
        JsonNode result = root.path("result");
        List<SearchResult> items = new ArrayList<>();
        for (JsonNode gallery : result) {
            items.add(toSearchResult(gallery));
        }
        // A full page means there is very likely another one — the API reports no "has next" flag.
        int perPage = root.path("per_page").asInt(DEFAULT_PER_PAGE);
        return new SeriesPage(items, result.size() >= perPage);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        JsonNode gallery = getJson(API_URL + "/gallery/" + galleryId(seriesExternalId));
        if (gallery == null || gallery.path("id").isMissingNode()) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        return toSearchResult(gallery);
    }

    private SearchResult toSearchResult(JsonNode gallery) {
        String externalId = "/g/" + gallery.path("id").asString("") + "/";
        JsonNode title = gallery.path("title");
        String display = firstNonBlank(
            title.path("english").asString(""),
            title.path("pretty").asString(""),
            title.path("japanese").asString(""));

        Map<String, List<String>> tagsByType = tagsByType(gallery.path("tags"));
        String artists = join(tagsByType.get("artist"));

        StringBuilder description = new StringBuilder();
        appendTags(description, "Parody", tagsByType.get("parody"));
        appendTags(description, "Characters", tagsByType.get("character"));
        appendTags(description, "Group", tagsByType.get("group"));
        description.append("Pages: ").append(gallery.path("num_pages").asInt(0)).append('\n');
        description.append("Favorites: ").append(gallery.path("num_favorites").asInt(0));

        List<String> genres = tagsByType.getOrDefault("tag", List.of());
        String mediaId = gallery.path("media_id").asString("");
        String cover = mediaId.isBlank() ? null
            : THUMB_URL + "/" + mediaId + "/thumb." + extension(gallery.path("images").path("thumbnail"));

        return new SearchResult(id(), externalId, display.isBlank() ? externalId : display,
            description.toString(), cover, artists, artists, genres,
            // Doujinshi are one-shots: there is never anything more to publish.
            SeriesStatus.COMPLETED, Map.of());
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        String galleryId = galleryId(seriesExternalId);
        JsonNode gallery = getJson(API_URL + "/gallery/" + galleryId);
        if (gallery == null || gallery.path("id").isMissingNode()) {
            return List.of();
        }
        // An nhentai gallery is a single readable unit — the gallery itself is the one "chapter".
        return List.of(new SourceChapter("/g/" + galleryId + "/", "Chapter 1", 1.0, null,
            uploadDate(gallery.path("upload_date").asLong(0L)), Map.of()));
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        JsonNode gallery = getJson(API_URL + "/gallery/" + galleryId(chapterExternalId));
        if (gallery == null) {
            return List.of();
        }
        String mediaId = gallery.path("media_id").asString("");
        if (mediaId.isBlank()) {
            return List.of();
        }
        Map<String, String> headers = imageHeaders();
        List<SourcePage> pages = new ArrayList<>();
        int index = 0;
        for (JsonNode image : gallery.path("images").path("pages")) {
            // Page files are numbered from 1 in gallery order; the API only carries their type.
            pages.add(new SourcePage(index, IMG_URL + "/" + mediaId + "/" + (index + 1) + "." + extension(image),
                headers));
            index++;
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return imageHeaders();
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private static Map<String, String> imageHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
    }

    /** {@code /g/123456/} (as stored by Kodex and Mihon alike) → {@code 123456}. */
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
            String name = tag.path("name").asString("");
            if (name.isBlank()) {
                continue;
            }
            byType.computeIfAbsent(tag.path("type").asString(""), k -> new ArrayList<>()).add(name);
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

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    /** The image's file extension, from the one-letter {@code t}ype the API reports. */
    private static String extension(JsonNode image) {
        return switch (image.path("t").asString("j")) {
            case "p" -> "png";
            case "g" -> "gif";
            case "w" -> "webp";
            default -> "jpg";
        };
    }

    private static LocalDate uploadDate(long epochSeconds) {
        return epochSeconds <= 0 ? null : Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private JsonNode getJson(String url) {
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
            return MAPPER.readTree(body.string());
        } catch (ProviderRateLimitException e) {
            throw e;
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
        throw new ProviderRateLimitException("nhentai rate limit (HTTP 429)", retryAfter);
    }
}
