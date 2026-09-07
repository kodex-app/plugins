package dev.kodex.plugin.hentaifox;

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
import dev.kodex.spi.common.http.SourceUnavailableException;
import dev.kodex.spi.common.http.HttpClientProvider;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class HentaiFoxSource implements ContentSource {

    static final String BASE_URL = "https://hentaifox.com";
    private static final String DOMAIN = "hentaifox.com";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Any run of special chars that precedes an alphanumeric/quote — collapsed to '+' (search term joiner).
    private static final Pattern SPECIAL_BEFORE_WORD = Pattern.compile("[^a-zA-Z0-9\"]+(?=[a-zA-Z0-9\"])");

    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    /** The site's own language slug used in browse URLs and search terms ({@code english}/{@code japanese}). */
    protected abstract String mangaLang();

    @Override
    public abstract String language();

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
        return "HentaiFox";
    }

    @Override
    public boolean adultContent() {
        return true;
    }

    @Override
    public String website() {
        return BASE_URL;
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        return mangaList(BASE_URL + "/language/" + mangaLang() + "/popular/pag/" + Math.max(1, page) + "/");
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return mangaList(BASE_URL + "/language/" + mangaLang() + "/pag/" + Math.max(1, page) + "/");
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        HttpUrl url = HttpUrl.get(BASE_URL + "/search/").newBuilder()
            .addEncodedQueryParameter("q", buildQueryString(query))
            .addQueryParameter("page", String.valueOf(Math.max(1, page)))
            .build();
        return mangaList(url.toString());
    }

    /**
     * Mirrors HentaiFox's {@code buildQueryString}: join the query and the language slug with {@code +},
     * collapsing any special-char run before a word into {@code +} so the site reads them as AND terms.
     */
    private String buildQueryString(String query) {
        List<String> terms = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            terms.add(query);
        }
        terms.add(mangaLang());
        StringBuilder sb = new StringBuilder();
        for (String term : terms) {
            String cleaned = SPECIAL_BEFORE_WORD.matcher(term.trim()).replaceAll("+").replace(" ", "");
            if (cleaned.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(cleaned);
        }
        return sb.toString();
    }

    private SeriesPage mangaList(String url) {
        Document doc = getHtml(url);
        List<SearchResult> items = new ArrayList<>();
        for (Element el : doc.select("div.thumb")) {
            Element caption = el.selectFirst(".caption");
            Element link = el.selectFirst(".inner_thumb a");
            if (caption == null || link == null) {
                continue;
            }
            Element img = el.selectFirst(".inner_thumb img");
            items.add(new SearchResult(id(), relative(link.attr("abs:href")), caption.text(),
                null, img != null ? imgAttr(img) : null, null, null,
                List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        boolean hasNextPage = doc.selectFirst(".pagination li.active + li:not(.disabled)") != null;
        return new SeriesPage(items, hasNextPage);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId);
        Element top = doc.selectFirst(".gallery_top");
        if (top == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.COMPLETED, Map.of());
        }
        Element titleEl = top.selectFirst("h1");
        String title = titleEl != null ? titleEl.text() : seriesExternalId;
        Element coverImg = top.selectFirst(".cover img");
        String cover = coverImg != null ? imgAttr(coverImg) : null;
        String tags = getInfo(top, "tags");
        String artists = getInfo(top, "artists");

        StringBuilder desc = new StringBuilder();
        for (String key : List.of("parodies", "characters", "languages", "categories")) {
            String value = getInfo(top, key);
            if (!value.isBlank()) {
                if (desc.length() > 0) {
                    desc.append("\n\n");
                }
                desc.append(capitalize(key)).append(": ").append(value);
            }
        }

        List<String> genres = tags.isBlank() ? List.of() : List.of(tags.split(",\\s*"));
        return new SearchResult(id(), seriesExternalId, title,
            desc.length() == 0 ? null : desc.toString(), cover,
            artists.isBlank() ? null : artists, null, genres,
            SeriesStatus.COMPLETED, Map.of());
    }

    /** {@code ul.<tag> a} → comma-joined names (with any {@code .split_tag} suffix), as HentaiFox does. */
    private static String getInfo(Element root, String tag) {
        List<String> values = new ArrayList<>();
        for (Element a : root.select("ul." + tag + " a")) {
            String name = a.ownText();
            String split = a.select(".split_tag").text().replaceFirst("^\\|\\s*", "").trim();
            String combined = split.isBlank() ? name : name + ", " + split;
            if (!combined.isBlank()) {
                values.add(combined);
            }
        }
        return String.join(", ", values);
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        // A HentaiFox gallery is a single readable unit; the gallery page itself is the "chapter".
        return List.of(new SourceChapter(seriesExternalId, "Chapter", null, null, null, Map.of()));
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + chapterExternalId);
        List<SourcePage> pages = new ArrayList<>();
        Map<String, String> headers = Map.of("Referer", BASE_URL + "/", "User-Agent", USER_AGENT);
        String json = parseJson(doc);
        if (json != null) {
            String loadDir = inputIdValueOf(doc, "load_dir");
            String loadId = inputIdValueOf(doc, "load_id");
            String server = serverHost(doc);
            if (server != null) {
                String imagesUri = "https://" + server + "/" + loadDir + "/" + loadId;
                try {
                    JsonNode images = MAPPER.readTree(json);
                    List<String> urls = new ArrayList<>();
                    List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(images.properties());
                    entries.sort(Comparator.comparingInt(e -> parseIntSafe(e.getKey())));
                    for (var entry : entries) {
                        String ext = entry.getValue().asText("").replace("\"", "").split(",")[0];
                        String url = imagesUri + "/" + entry.getKey() + "." + imageExt(ext);
                        urls.add(url);
                    }
                    for (int i = 0; i < urls.size(); i++) {
                        pages.add(new SourcePage(i, urls.get(i), headers));
                    }
                    if (!pages.isEmpty()) {
                        return pages;
                    }
                } catch (Exception ignored) {
                    // fall through to the thumbnail-based fallback
                }
            }
        }
        // Fallback: derive full images from the gallery thumbnails (drop the trailing "t" in the filename).
        var thumbs = doc.select(".gallery_thumb img");
        for (int i = 0; i < thumbs.size(); i++) {
            String src = thumbnailToFull(imgAttr(thumbs.get(i)));
            if (src != null && !src.isBlank()) {
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

    private static String imageExt(String ext) {
        return switch (ext) {
            case "p" -> "png";
            case "b" -> "bmp";
            case "g" -> "gif";
            case "w" -> "webp";
            default -> "jpg";
        };
    }

    private static String parseJson(Document doc) {
        Element script = doc.selectFirst("script:containsData(parseJSON)");
        if (script == null) {
            return null;
        }
        String data = script.data();
        int start = data.indexOf("$.parseJSON('");
        if (start < 0) {
            return null;
        }
        data = data.substring(start + "$.parseJSON('".length());
        int end = data.indexOf("');");
        return end < 0 ? null : data.substring(0, end).trim();
    }

    private static String inputIdValueOf(Document doc, String id) {
        Element input = doc.selectFirst("input[id=" + id + "]");
        return input != null ? input.attr("value") : "";
    }

    /** {@code m<n>.hentaifox.com} when a server number is present, else the cover image's host. */
    private static String serverHost(Document doc) {
        String serverNumber = inputIdValueOf(doc, "load_server");
        if (!serverNumber.isBlank()) {
            return "m" + serverNumber + "." + DOMAIN;
        }
        Element cover = doc.selectFirst(".cover img");
        if (cover != null) {
            try {
                return HttpUrl.get(imgAttr(cover)).host();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String imgAttr(Element el) {
        if (el.hasAttr("data-cfsrc")) {
            return el.attr("abs:data-cfsrc");
        }
        if (el.hasAttr("data-src")) {
            return el.attr("abs:data-src");
        }
        if (el.hasAttr("data-lazy-src")) {
            return el.attr("abs:data-lazy-src");
        }
        if (el.hasAttr("srcset")) {
            return el.attr("abs:srcset").split(" ")[0];
        }
        return el.attr("abs:src");
    }

    private static String thumbnailToFull(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        int dot = url.lastIndexOf('.');
        if (dot < 0) {
            return url;
        }
        String ext = url.substring(dot + 1);
        return url.replace("t." + ext, "." + ext);
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Fetches a page. A failed request throws instead of returning null: a Cloudflare 403, a moved
     * domain, or a timeout used to come back as an empty feed, which the apps can only render as
     * "this source has nothing".
     */
    private Document getHtml(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            String payload = body == null ? null : body.string();
            if (!res.isSuccessful() || payload == null) {
                throw SourceUnavailableException.http(displayName(), url, res.code(), payload);
            }
            return Jsoup.parse(payload, url);
        } catch (RuntimeException e) {
            throw e; // already the right failure (unavailable / rate limit)
        } catch (Exception e) {
            throw SourceUnavailableException.transport(displayName(), url, e);
        }
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
}
