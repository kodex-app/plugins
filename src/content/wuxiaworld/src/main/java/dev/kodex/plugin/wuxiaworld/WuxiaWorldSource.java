package dev.kodex.plugin.wuxiaworld;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.pf4j.Extension;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Extension
public class WuxiaWorldSource implements ContentSource {

    private static final String BASE_URL = "https://www.wuxiaworld.com";
    private static final String API_URL = "https://api2.wuxiaworld.com/wuxiaworld.api.v2.";
    private static final MediaType GRPC = MediaType.get("application/grpc-web+proto");
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int PAGE_SIZE = 24;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

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
        return "WuxiaWorld";
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
        return false; // WuxiaWorld's API has no "latest" feed; browse is the full catalogue.
    }

    // ---- Browse / search (REST JSON) -------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        // /api/novels returns the whole catalogue in one shot; paginate client-side for a sane UI.
        List<SearchResult> all = parseNovels(getJson(BASE_URL + "/api/novels"));
        int from = Math.max(0, (Math.max(1, page) - 1) * PAGE_SIZE);
        if (from >= all.size()) {
            return SeriesPage.empty();
        }
        int to = Math.min(all.size(), from + PAGE_SIZE);
        return new SeriesPage(all.subList(from, to), to < all.size());
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        if (query == null || query.isBlank()) {
            return popular(page, settings);
        }
        String url = BASE_URL + "/api/novels/search?query=" + urlEncode(query.trim());
        return new SeriesPage(parseNovels(getJson(url)), false);
    }

    private List<SearchResult> parseNovels(JsonNode root) {
        List<SearchResult> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            return out;
        }
        for (JsonNode n : items) {
            String slug = text(n, "slug");
            String name = text(n, "name");
            if (slug == null || name == null) {
                continue;
            }
            out.add(new SearchResult(id(), slug, name, null, text(n, "coverUrl"),
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        return out;
    }

    // ---- Details (gRPC GetNovel) -----------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Map<Integer, List<Object>> item = getNovel(seriesExternalId);
        if (item == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        String name = orElse(Proto.string(item, 2), seriesExternalId);
        String cover = Proto.wrappedString(item, 10);
        String description = Proto.wrappedString(item, 8);
        String synopsis = Proto.wrappedString(item, 9);
        String author = Proto.wrappedString(item, 13);
        List<String> genres = Proto.repeatedStrings(item, 16);
        String summary = htmlToText(join(description, synopsis));
        return new SearchResult(id(), seriesExternalId, name,
            summary.isBlank() ? null : summary, cover,
            author == null || author.isBlank() ? null : author, null, genres,
            // proto3 omits the default enum value (Finished=0) from the wire, so an absent status means Finished.
            mapStatus(Proto.longVal(item, 4, 0)), Map.of());
    }

    private static SeriesStatus mapStatus(long status) {
        // NovelItem.Status: Finished=0, Active=1, Hiatus=2, All=-1.
        return switch ((int) status) {
            case 1 -> SeriesStatus.ONGOING;
            case 2 -> SeriesStatus.ON_HIATUS;
            case 0 -> SeriesStatus.COMPLETED;
            default -> SeriesStatus.UNKNOWN;
        };
    }

    // ---- Chapters (gRPC GetChapterList) ----------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        List<SourceChapter> chapters = new ArrayList<>();
        Map<Integer, List<Object>> novel = getNovel(seriesExternalId);
        if (novel == null) {
            return chapters;
        }
        long novelId = Proto.longVal(novel, 1, 0);
        if (novelId == 0) {
            return chapters;
        }
        // karmaInfo(14).maxFreeChapter(3).units(1) — chapters past this are locked for anonymous requests.
        Map<Integer, List<Object>> karma = Proto.message(novel, 14);
        long freeChapter = 50;
        if (karma != null) {
            Map<Integer, List<Object>> maxFree = Proto.message(karma, 3);
            if (maxFree != null) {
                freeChapter = Proto.longVal(maxFree, 1, 50);
            }
        }

        byte[] req = new Proto.Writer().varint(1, novelId).toBytes(); // GetChapterListRequest{ novelId = 1 }
        Map<Integer, List<Object>> resp = grpc("Chapters/GetChapterList", req);

        for (Map<Integer, List<Object>> group : Proto.repeatedMessages(resp, 1)) {
            String volume = Proto.string(group, 2);
            for (Map<Integer, List<Object>> ch : Proto.repeatedMessages(group, 6)) {
                String slug = Proto.string(ch, 3);
                String name = Proto.string(ch, 2);
                if (slug == null || name == null) {
                    continue;
                }
                long offset = Proto.longVal(ch, 17, chapters.size());
                Map<Integer, List<Object>> numberMsg = Proto.message(ch, 4);
                long number = numberMsg != null ? Proto.longVal(numberMsg, 1, 0) : 0;
                if (isLocked(ch, number, freeChapter)) {
                    name = name + " 🔒";
                }
                LocalDate date = null;
                Map<Integer, List<Object>> published = Proto.message(ch, 18);
                if (published != null) {
                    long seconds = Proto.longVal(published, 1, 0);
                    if (seconds > 0) {
                        date = Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).toLocalDate();
                    }
                }
                Map<String, String> attrs = volume == null || volume.isBlank() ? Map.of() : Map.of("volume", volume);
                chapters.add(new SourceChapter(seriesExternalId + "/" + slug, name, (double) offset, null, date, attrs));
            }
        }
        return chapters;
    }

    /** Locked if the API explicitly says so, or (anonymous, no unlock info) the number is past the free limit. */
    private static boolean isLocked(Map<Integer, List<Object>> ch, long number, long freeChapter) {
        Map<Integer, List<Object>> related = Proto.message(ch, 16); // RelatedChapterUserInfo
        if (related != null) {
            Map<Integer, List<Object>> unlocked = Proto.message(related, 1); // isChapterUnlocked: BoolValue
            if (unlocked != null) {
                return Proto.longVal(unlocked, 1, 0) == 0; // value == false
            }
        }
        return number > freeChapter;
    }

    // ---- Content (gRPC GetChapter) ---------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        return List.of(); // BOOK source: text comes from chapterContent
    }

    @Override
    public SourceChapterContent chapterContent(String chapterExternalId, ProviderSettings settings) {
        int slash = chapterExternalId.indexOf('/');
        if (slash < 0) {
            return new SourceChapterContent(null, "");
        }
        String novelSlug = chapterExternalId.substring(0, slash);
        String chapterSlug = chapterExternalId.substring(slash + 1);

        // GetChapterRequest{ chapterProperty(1) = GetChapterByProperty{ slugs(2) = { novelSlug(1), chapterSlug(2) } } }
        Proto.Writer slugs = new Proto.Writer().string(1, novelSlug).string(2, chapterSlug);
        Proto.Writer property = new Proto.Writer().message(2, slugs);
        byte[] req = new Proto.Writer().message(1, property).toBytes();

        Map<Integer, List<Object>> resp = grpc("Chapters/GetChapter", req);
        Map<Integer, List<Object>> item = Proto.message(resp, 1);
        if (item == null) {
            return new SourceChapterContent(null, "");
        }
        String content = Proto.wrappedString(item, 5); // ChapterItem.content: StringValue
        String name = Proto.string(item, 2);
        return new SourceChapterContent(name, content == null ? "" : content);
    }

    // ---- HTTP helpers ----------------------------------------------------------------------------

    /** GetNovel(slug) → the NovelItem message, or {@code null} on failure. */
    private Map<Integer, List<Object>> getNovel(String slug) {
        byte[] req = new Proto.Writer().string(2, slug).toBytes(); // GetNovelRequest{ slug = 2 }
        Map<Integer, List<Object>> resp = grpc("Novels/GetNovel", req);
        return Proto.message(resp, 1); // GetNovelResponse.item
    }

    /** POST a gRPC-web call and return the decoded response message (empty map on failure). */
    private Map<Integer, List<Object>> grpc(String method, byte[] message) {
        Request req = new Request.Builder()
            .url(API_URL + method)
            .post(RequestBody.create(Proto.frame(message), GRPC))
            .header("Content-Type", "application/grpc-web+proto")
            .header("X-Grpc-Web", "1")
            .header("Accept", "application/grpc-web+proto")
            .header("User-Agent", USER_AGENT)
            .header("Origin", BASE_URL)
            .header("Referer", BASE_URL + "/")
            .build();
        try (Response res = http().newCall(req).execute()) {
            throwIfRateLimited(res);
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                return Map.of();
            }
            return Proto.decode(Proto.unframe(body.bytes()));
        } catch (ProviderRateLimitException e) {
            throw e; // must reach the core's retry/backoff handling — don't swallow with the IO failures below
        } catch (Exception e) {
            return Map.of(); // fail soft, per the SPI contract
        }
    }

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
            if (!res.isSuccessful() || body == null) {
                return null;
            }
            return mapper.readTree(body.string());
        } catch (ProviderRateLimitException e) {
            throw e; // must reach the core's retry/backoff handling — don't swallow with the IO failures below
        } catch (Exception e) {
            return null;
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
        throw new ProviderRateLimitException("WuxiaWorld rate limit (HTTP 429)", retryAfter);
    }

    // ---- misc ------------------------------------------------------------------------------------

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String htmlToText(String html) {
        return html.isBlank() ? "" : Jsoup.parse(html).text().trim();
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        return (x + "\n\n" + y).trim();
    }

    private static String orElse(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
