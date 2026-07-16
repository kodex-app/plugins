package dev.kodex.ext.manhwazone;

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
import dev.kodex.spi.content.filter.FilterList;
import dev.kodex.spi.common.http.HttpClientProvider;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.pf4j.Extension;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class ManhwaZoneSource implements ContentSource {

    private static final String BASE_URL = "https://manhwazone.com";
    private static final String IMAGE_CDN = "https://img.mangalaxy.net/_img";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern RS_CONF = Pattern.compile("__RS_CONF__\\s*=\\s*(\\{.*?\\})\\s*;");
    private static final Pattern AUTHOR_LD =
        Pattern.compile("\"author\":\\s*\\[\\s*\\{\"@type\":\"Person\",\"name\":\"([^\"]+)\"");

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
    public String displayName() {
        return "ManhwaZone";
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
        return "en";
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return parseMangaList(getHtml(BASE_URL + "/series?sortBy=popularity&page=" + Math.max(1, page)));
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return parseMangaList(getHtml(BASE_URL + "/series?sortBy=latest&page=" + Math.max(1, page)));
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/series").newBuilder()
            .addQueryParameter("page", String.valueOf(Math.max(1, page)));
        if (query != null && !query.isBlank()) {
            url.addQueryParameter("keyword", query.trim());
        }
        Filters.applyToUrl(url, filters);
        return parseMangaList(getHtml(url.build().toString()));
    }

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList();
    }

    private SeriesPage parseMangaList(Document doc) {
        if (doc == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> mangas = new ArrayList<>();
        var elements = doc.select("article.group");
        for (Element element : elements) {
            Element titleEl = element.selectFirst(".min-w-0 > a.font-semibold");
            Element a = element.selectFirst("a");
            if (a == null) {
                continue;
            }
            Element img = element.selectFirst("img");
            String thumbnail = img != null ? img.attr("abs:src") : null;
            mangas.add(new SearchResult(id(), relative(a.attr("abs:href")),
                titleEl != null ? titleEl.text() : a.text(), null,
                thumbnail == null || thumbnail.isBlank() ? null : thumbnail,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        boolean hasNextPage = doc.selectFirst("a[rel=next], nav a:contains(›)") != null || elements.size() >= 24;
        return new SeriesPage(mangas, hasNextPage);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId);
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        Element titleEl = doc.selectFirst("h1.page-title");
        Element descEl = doc.selectFirst("p.page-subtitle");
        Element cover = doc.selectFirst("img.aspect-\\[7\\/10\\], figure.relative img");
        String thumbnail = cover != null ? cover.attr("abs:src") : null;

        List<String> genres = new ArrayList<>();
        for (Element g : doc.select("a.badge-genre")) {
            genres.add(g.text());
        }

        Element statusEl = doc.selectFirst("span.badge-sm, span:contains(On Going), span:contains(Completed)");
        SeriesStatus status = parseStatus(statusEl != null ? statusEl.text().trim() : null);

        String author = null;
        Element jsonLd = doc.selectFirst("script[type=application/ld+json]");
        if (jsonLd != null) {
            Matcher m = AUTHOR_LD.matcher(jsonLd.data());
            if (m.find() && !m.group(1).equalsIgnoreCase("unknown")) {
                author = m.group(1);
            }
        }

        return new SearchResult(id(), seriesExternalId,
            titleEl != null ? titleEl.text() : seriesExternalId,
            descEl != null ? descEl.text() : null,
            thumbnail == null || thumbnail.isBlank() ? null : thumbnail,
            author, null, genres, status, Map.of());
    }

    private static SeriesStatus parseStatus(String text) {
        if (text == null) {
            return SeriesStatus.UNKNOWN;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "on going", "ongoing", "currently publishing" -> SeriesStatus.ONGOING;
            case "completed", "finished" -> SeriesStatus.COMPLETED;
            case "on hiatus" -> SeriesStatus.ON_HIATUS;
            case "discontinued", "cancelled" -> SeriesStatus.CANCELLED;
            default -> SeriesStatus.UNKNOWN;
        };
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        List<SourceChapter> chapters = new ArrayList<>();
        Document doc = getHtml(BASE_URL + seriesExternalId);
        if (doc == null) {
            return chapters;
        }
        Element wireDiv = doc.selectFirst("div[wire:snapshot][wire:id][wire:init=bootLoad]");
        if (wireDiv == null) {
            return chapters;
        }
        Element csrf = doc.selectFirst("meta[name=csrf-token]");
        String csrfToken = csrf != null ? csrf.attr("content") : "";
        String snapshot = wireDiv.attr("wire:snapshot");

        String payload = buildBootLoadPayload(csrfToken, snapshot);
        String body = postJson(BASE_URL + "/livewire/update", payload);
        if (body == null) {
            return chapters;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode components = root.path("components");
            if (!components.isArray() || components.isEmpty()) {
                return chapters;
            }
            String snapshotStr = components.get(0).path("snapshot").asText(null);
            if (snapshotStr == null) {
                return chapters;
            }
            JsonNode chaptersNode = MAPPER.readTree(snapshotStr).path("data").path("chapters");
            // chapters = [ [ [chapterObj, meta], ... ], meta ]
            JsonNode outer = chaptersNode.isArray() && !chaptersNode.isEmpty() ? chaptersNode.get(0) : null;
            if (outer == null || !outer.isArray()) {
                return chapters;
            }
            for (JsonNode tuple : outer) {
                JsonNode chapter = tuple.isArray() && !tuple.isEmpty() ? tuple.get(0) : tuple;
                String webUrl = text(chapter, "web_url");
                if (webUrl == null) {
                    continue;
                }
                String name = text(chapter, "name");
                LocalDate date = parseDate(text(chapter, "published"));
                chapters.add(new SourceChapter(relative(webUrl), name == null ? "Chapter" : name,
                    parseNumber(name), null, date, Map.of()));
            }
        } catch (Exception ignored) {
            // fail soft
        }
        return chapters;
    }

    private String buildBootLoadPayload(String csrfToken, String snapshot) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("_token", csrfToken);
        ArrayNode components = root.putArray("components");
        ObjectNode component = components.addObject();
        component.put("snapshot", snapshot);
        component.set("updates", MAPPER.createObjectNode());
        ArrayNode calls = component.putArray("calls");
        ObjectNode call = calls.addObject();
        call.put("path", "");
        call.put("method", "bootLoad");
        call.putArray("params");
        return root.toString();
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        List<SourcePage> pages = new ArrayList<>();
        Document doc = getHtml(BASE_URL + chapterExternalId);
        if (doc == null) {
            return pages;
        }
        Map<String, String> headers = Map.of("Referer", BASE_URL + "/", "User-Agent", USER_AGENT);

        Element script = doc.selectFirst("script:containsData(__RS_CONF__)");
        if (script != null) {
            try {
                Matcher m = RS_CONF.matcher(script.data());
                if (m.find()) {
                    JsonNode conf = MAPPER.readTree(m.group(1));
                    String p = text(conf, "p");
                    String expire = text(conf, "expire");
                    String signature = text(conf, "signature");
                    int tt = conf.path("tt").asInt(0);
                    if (p != null && expire != null && signature != null && tt > 0) {
                        for (int i = 1; i <= tt; i++) {
                            String pageStr = String.format(Locale.ROOT, "%03d", i);
                            String imageUrl = IMAGE_CDN + "/" + p + "/" + pageStr + ".webp?e=" + expire + "&s=" + signature;
                            pages.add(new SourcePage(i - 1, imageUrl, headers));
                        }
                        return pages;
                    }
                }
            } catch (Exception ignored) {
                // fall through to data-src fallback
            }
        }

        var imgs = doc.select("img.lazy-image[data-src]");
        for (int i = 0; i < imgs.size(); i++) {
            String src = imgs.get(i).attr("abs:data-src");
            if (!src.isBlank()) {
                pages.add(new SourcePage(i, src, headers));
            }
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private Document getHtml(String url) {
        String body = getString(url);
        return body == null ? null : Jsoup.parse(body, url);
    }

    private String getString(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .get().build();
        return execute(req);
    }

    private String postJson(String url, String json) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .header("Accept", "application/json")
            .post(RequestBody.create(json, JSON))
            .build();
        return execute(req);
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

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() && v.isValueNode() ? v.asText() : null;
    }

    private static String relative(String absUrl) {
        try {
            URI u = URI.create(absUrl);
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            return u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
        } catch (Exception e) {
            return absUrl;
        }
    }

    private static Double parseNumber(String name) {
        if (name == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(name);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
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
}
