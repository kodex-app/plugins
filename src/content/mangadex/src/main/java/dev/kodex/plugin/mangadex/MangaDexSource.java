package dev.kodex.plugin.mangadex;

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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.pf4j.Extension;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Extension
public class MangaDexSource implements ContentSource {

    private static final String API = "https://api.mangadex.org";
    private static final String COVERS = "https://uploads.mangadex.org/covers";
    private static final String SITE = "https://mangadex.org";
    private static final String LANG = "en";
    private static final int LIMIT = 24;
    private static final String CONTENT_RATING = "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica";
    // MangaDex asks API clients for an honest, descriptive User-Agent — spoofed browser UAs are rejected by their WAF.
    private static final String USER_AGENT = "Kodex/1.0 (+https://github.com/kodex; MangaDex content source)";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(MangaDexSource.class.getName());

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
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
        return "MangaDex";
    }

    @Override
    public String website() {
        return SITE;
    }

    @Override
    public String language() {
        return LANG;
    }

    /**
     * MangaDex ids are UUIDs living at the tail of Mihon's stored urls ({@code /title/<id>},
     * {@code /chapter/<id>}); Kodex keys series and chapters by that bare UUID. The Mihon id is derived from
     * {@link #displayName()}/{@link #language()} ({@code "MangaDex"}/{@code "en"}) — MangaDex is a
     * multi-language upstream extension sharing one name, and the global UUID is the same regardless of which
     * language source a favorite came from, so other-language favorites still match via the display-name fallback.
     */
    @Override
    public String toSeriesExternalId(String mihonMangaUrl) {
        return lastSegment(mihonMangaUrl);
    }

    @Override
    public String toChapterExternalId(String mihonChapterUrl) {
        return lastSegment(mihonChapterUrl);
    }

    private static String lastSegment(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return mangaList(API + "/manga?order[followedCount]=desc" + commonMangaParams(page));
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return mangaList(API + "/manga?order[latestUploadedChapter]=desc" + commonMangaParams(page));
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        String q = query == null ? "" : query.trim();
        // An empty title= param is rejected by the API (400); fall back to a popularity-ordered browse.
        String base = q.isEmpty()
            ? API + "/manga?order[followedCount]=desc"
            : API + "/manga?title=" + enc(q);
        return mangaList(base + commonMangaParams(page));
    }

    private String commonMangaParams(int page) {
        return "&availableTranslatedLanguage[]=" + LANG
            + "&limit=" + LIMIT
            + "&offset=" + (Math.max(1, page) - 1) * LIMIT
            + "&includes[]=cover_art"
            + "&hasAvailableChapters=true"
            + CONTENT_RATING;
    }

    private SeriesPage mangaList(String url) {
        JsonNode root = getJson(url);
        if (root == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        for (JsonNode d : root.path("data")) {
            items.add(toResult(d));
        }
        double total = root.path("total").asDouble(0);
        double offset = root.path("offset").asDouble(0);
        double limit = root.path("limit").asDouble(0);
        boolean hasNext = offset + limit < total;
        return new SeriesPage(items, hasNext);
    }

    private SearchResult toResult(JsonNode data) {
        String mangaId = text(data.get("id"));
        JsonNode attrs = data.path("attributes");
        String title = firstString(attrs.path("title"));
        String cover = coverUrl(mangaId, data);
        List<String> genres = new ArrayList<>();
        for (JsonNode tag : attrs.path("tags")) {
            String name = pickLang(tag.path("attributes").path("name"));
            if (name != null) {
                genres.add(name);
            }
        }
        return new SearchResult(id(), mangaId, title != null ? title : mangaId, null, cover,
            null, null, genres, parseStatus(text(attrs.get("status"))), Map.of());
    }

    private static SeriesStatus parseStatus(String status) {
        if (status == null) {
            return SeriesStatus.UNKNOWN;
        }
        return switch (status) {
            case "ongoing" -> SeriesStatus.ONGOING;
            case "completed" -> SeriesStatus.COMPLETED;
            case "hiatus" -> SeriesStatus.ON_HIATUS;
            case "cancelled" -> SeriesStatus.CANCELLED;
            default -> SeriesStatus.UNKNOWN;
        };
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        JsonNode root = getJson(API + "/manga/" + seriesExternalId
            + "?includes[]=cover_art&includes[]=author&includes[]=artist");
        if (root == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        JsonNode data = root.path("data");
        JsonNode attrs = data.path("attributes");
        String title = firstString(attrs.path("title"));
        String description = pickLang(attrs.path("description"));

        List<String> tags = new ArrayList<>();
        for (JsonNode tag : attrs.path("tags")) {
            String name = pickLang(tag.path("attributes").path("name"));
            if (name != null) {
                tags.add(name);
            }
        }

        String author = null;
        String artist = null;
        for (JsonNode rel : data.path("relationships")) {
            String type = text(rel.get("type"));
            if ("author".equals(type) && author == null) {
                author = text(rel.path("attributes").get("name"));
            } else if ("artist".equals(type) && artist == null) {
                artist = text(rel.path("attributes").get("name"));
            }
        }

        return new SearchResult(id(), seriesExternalId, title != null ? title : seriesExternalId,
            description, coverUrl(seriesExternalId, data),
            author != null && !author.isBlank() ? author : null,
            artist != null && !artist.isBlank() ? artist : null,
            tags, parseStatus(text(attrs.get("status"))), Map.of());
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        List<SourceChapter> chapters = new ArrayList<>();
        int offset = 0;
        int total = Integer.MAX_VALUE;
        while (offset < total) {
            String url = API + "/manga/" + seriesExternalId + "/feed?translatedLanguage[]=" + LANG
                + "&order[volume]=desc&order[chapter]=desc&limit=500&offset=" + offset
                + "&includeExternalUrl=0&includes[]=scanlation_group" + CONTENT_RATING;
            JsonNode root = getJson(url);
            if (root == null) {
                break;
            }
            JsonNode data = root.path("data");
            for (JsonNode item : data) {
                JsonNode attrs = item.path("attributes");
                if (text(attrs.get("externalUrl")) != null || attrs.path("pages").asDouble(0) <= 0) {
                    continue; // external (off-site) or empty chapters can't be read here
                }
                String chapterId = text(item.get("id"));
                String number = text(attrs.get("chapter"));
                String chTitle = text(attrs.get("title"));
                chapters.add(new SourceChapter(chapterId, chapterName(number, chTitle),
                    parseDouble(number), scanlationGroups(item), parseDate(text(attrs.get("publishAt"))), Map.of()));
            }
            total = (int) root.path("total").asDouble(0);
            offset += 500;
            if (data.size() == 0) {
                break;
            }
        }
        return chapters;
    }

    private static String chapterName(String number, String title) {
        String base = number != null && !number.isBlank() ? "Chapter " + number : "Oneshot";
        return title != null && !title.isBlank() ? base + ": " + title : base;
    }

    /**
     * The scanlation group name(s) on a chapter's {@code scanlation_group} relationships (requested via
     * {@code includes[]=scanlation_group}), joined with ", " when a chapter is a joint release. Null when
     * no group is credited — the core treats that as "unknown".
     */
    private static String scanlationGroups(JsonNode chapterItem) {
        List<String> names = new ArrayList<>();
        for (JsonNode rel : chapterItem.path("relationships")) {
            if ("scanlation_group".equals(text(rel.get("type")))) {
                String name = text(rel.path("attributes").get("name"));
                if (name != null && !name.isBlank() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        JsonNode root = getJson(API + "/at-home/server/" + chapterExternalId);
        List<SourcePage> pages = new ArrayList<>();
        if (root == null) {
            return pages;
        }
        String baseUrl = text(root.get("baseUrl"));
        JsonNode chapter = root.path("chapter");
        String hash = text(chapter.get("hash"));
        if (baseUrl == null || hash == null) {
            return pages;
        }
        Map<String, String> headers = Map.of("Referer", SITE + "/", "User-Agent", USER_AGENT);
        int i = 0;
        for (JsonNode fileNode : chapter.path("data")) {
            String file = text(fileNode);
            if (file != null) {
                pages.add(new SourcePage(i, baseUrl + "/data/" + hash + "/" + file, headers));
            }
            i++;
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        // The cover CDN (uploads.mangadex.org) rejects spoofed browser User-Agents the same way the API
        // does; have the core fetch covers with our honest UA + Referer instead of its browser default.
        return Map.of("User-Agent", USER_AGENT, "Referer", SITE + "/");
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private JsonNode getJson(String url) {
        Request req = new Request.Builder()
            .url(encode(url))
            .header("User-Agent", USER_AGENT)
            .header("Referer", SITE + "/")
            .header("Accept", "application/json")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            String payload = body != null ? body.string() : null;
            if (!res.isSuccessful() || payload == null) {
                int code = res.code();
                String snippet = payload == null ? "(no body)"
                    : payload.substring(0, Math.min(500, payload.length()));
                LOG.log(System.Logger.Level.WARNING,
                    () -> "MangaDex HTTP " + code + " for " + url + " — " + snippet);
                return null;
            }
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, () -> "MangaDex request failed for " + url, e);
            return null; // fail soft, per the SPI contract
        }
    }

    /** Cover file name lives on the {@code cover_art} relationship (requested via {@code includes[]}). */
    private static String coverUrl(String mangaId, JsonNode data) {
        for (JsonNode rel : data.path("relationships")) {
            if ("cover_art".equals(text(rel.get("type")))) {
                String file = text(rel.path("attributes").get("fileName"));
                if (file != null) {
                    return COVERS + "/" + mangaId + "/" + file + ".512.jpg";
                }
            }
        }
        return null;
    }

    /** Localized field: prefer English, else the first available value. */
    private static String pickLang(JsonNode localized) {
        if (localized == null || !localized.isObject() || localized.isEmpty()) {
            return null;
        }
        String en = text(localized.get(LANG));
        if (en != null) {
            return en;
        }
        for (JsonNode v : localized) {
            String s = text(v);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private static String firstString(JsonNode localized) {
        return pickLang(localized);
    }

    /** A scalar JSON node's text, or null when the field is absent/null/non-scalar. */
    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asText() : null;
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

    /** MangaDex query params use literal {@code [ ]}; percent-encode them so the URL parses cleanly. */
    private static String encode(String url) {
        return url.replace("[", "%5B").replace("]", "%5D");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
