package dev.kodex.plugin.hentainexus;

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
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.pf4j.Extension;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Extension
public class HentaiNexusSource implements ContentSource {

    private static final String BASE_URL = "https://hentainexus.com";
    private static final String POPULAR_NOW_PATH = "/explore/hot";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TAG_COUNT = Pattern.compile("\\s*\\([\\d,]+\\)$");
    private static final Pattern INIT_READER = Pattern.compile("initReader\\(\"([^\"]*)\"");
    private static final DateTimeFormatter PUBLISHED = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.US);

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
        return "HentaiNexus";
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

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList();
    }

    // ---- Browse / search -------------------------------------------------------------------------

    @Override
    public SeriesPage popular(int page, ProviderSettings settings) {
        // Page 1 is the curated "Popular Now" page; later pages reuse search with a "sort:popular" query
        // (the search feed is 1-based off that first page), mirroring popularMangaRequest upstream.
        if (Math.max(1, page) <= 1) {
            return mangaList(BASE_URL + POPULAR_NOW_PATH, true);
        }
        return searchData(page - 1, "sort:popular", getFilterList());
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return mangaList(BASE_URL + (page > 1 ? "/page/" + page : ""), false);
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        FilterList effective = (filters == null || filters.filters().isEmpty()) ? getFilterList() : filters;
        return searchData(Math.max(1, page), query == null ? "" : query, effective);
    }

    private SeriesPage searchData(int page, String query, FilterList filters) {
        int actualPage = page + Filters.pageOffset(filters);
        String q = (Filters.combineQuery(filters) + query).trim();
        HttpUrl.Builder url = HttpUrl.get(BASE_URL).newBuilder();
        if (actualPage > 1) {
            url.addPathSegments("page/" + actualPage);
        }
        url.addQueryParameter("q", q);
        return mangaList(url.build().toString(), false);
    }

    /** {@code isPopularNow} = the /explore/hot landing page, which always advertises a next page. */
    private SeriesPage mangaList(String url, boolean isPopularNow) {
        Document doc = getHtml(url);
        if (doc == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        for (Element el : doc.select(".container .column")) {
            Element link = el.selectFirst("a");
            Element titleEl = el.selectFirst(".card-header-title");
            if (link == null || titleEl == null) {
                continue;
            }
            Element img = el.selectFirst(".card-image img");
            items.add(new SearchResult(id(), relative(link.attr("abs:href")), titleEl.text(),
                null, img != null ? img.attr("abs:src") : null, null, null,
                List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        boolean hasNextPage = isPopularNow || doc.selectFirst("a.pagination-next[href]") != null;
        return new SeriesPage(items, hasNextPage);
    }

    // ---- Details ---------------------------------------------------------------------------------

    @Override
    public SearchResult seriesDetails(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + seriesExternalId);
        if (doc == null) {
            return new SearchResult(id(), seriesExternalId, seriesExternalId, null, null,
                null, null, List.of(), SeriesStatus.UNKNOWN, Map.of());
        }
        Element table = doc.selectFirst(".view-page-details");
        Element titleEl = doc.selectFirst("h1.title");
        String title = titleEl != null ? titleEl.text() : seriesExternalId;

        Set<String> people = new LinkedHashSet<>();
        List<String> genres = new ArrayList<>();
        String cover = null;
        StringBuilder description = new StringBuilder();
        if (table != null) {
            for (Element a : table.select("td.viewcolumn:contains(Author) + td a")) {
                people.add(a.ownText());
            }
            for (Element a : table.select("td.viewcolumn:contains(Artist) + td a")) {
                people.add(a.ownText());
            }
            for (Element a : table.select("span.tag a")) {
                genres.add(TAG_COUNT.matcher(a.text()).replaceAll("").trim());
            }
            for (String key : List.of("Circle", "Event", "Magazine", "Parody", "Publisher", "Pages", "Favorites")) {
                String value = cellText(table, key);
                if (value != null && !value.isBlank()) {
                    description.append(key).append(": ").append(value).append('\n');
                }
            }
            Element desc = table.selectFirst("td.viewcolumn:contains(Description) + td");
            if (desc != null && !desc.text().isBlank()) {
                description.append('\n').append(desc.text());
            }
        }
        Element coverImg = doc.selectFirst("figure.image img");
        if (coverImg != null) {
            cover = coverImg.attr("abs:src");
        }
        String author = people.isEmpty() ? null : String.join(", ", people);
        return new SearchResult(id(), seriesExternalId, title,
            description.length() == 0 ? null : description.toString().trim(), cover,
            author, null, genres, SeriesStatus.COMPLETED, Map.of());
    }

    private static String cellText(Element table, String key) {
        Element cell = table.selectFirst("td.viewcolumn:contains(" + key + ") + td");
        if (cell == null) {
            return null;
        }
        String own = cell.ownText();
        if (!own.isEmpty()) {
            return own;
        }
        Element a = cell.selectFirst("a");
        return a != null ? a.ownText() : null;
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        String idSegment = lastSegment(seriesExternalId);
        LocalDate published = null;
        Document doc = getHtml(BASE_URL + seriesExternalId);
        if (doc != null) {
            Element table = doc.selectFirst(".view-page-details");
            if (table != null) {
                Element cell = table.selectFirst("td.viewcolumn:contains(Published) + td");
                if (cell != null) {
                    published = parseDate(cell.text());
                }
            }
        }
        // HentaiNexus galleries have exactly one readable "chapter" at /read/<id>.
        return List.of(new SourceChapter("/read/" + idSegment, "Chapter", null, null, published, Map.of()));
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + chapterExternalId);
        List<SourcePage> pages = new ArrayList<>();
        if (doc == null) {
            return pages;
        }
        Element script = doc.selectFirst("script:containsData(initReader)");
        if (script == null) {
            return pages;
        }
        var m = INIT_READER.matcher(script.data());
        if (!m.find()) {
            return pages;
        }
        String decrypted;
        try {
            decrypted = Decryptor.decrypt(m.group(1));
        } catch (Exception e) {
            return pages;
        }
        Map<String, String> headers = Map.of("Referer", BASE_URL + "/", "User-Agent", USER_AGENT);
        try {
            JsonNode root = MAPPER.readTree(decrypted);
            int index = 0;
            for (JsonNode node : root) {
                if (!"image".equals(node.path("type").asText())) {
                    continue;
                }
                // Newer entries may carry only "image_fallback" instead of "image" (upstream #16960).
                String image = node.path("image").asText(null);
                if (image == null || image.isBlank()) {
                    image = node.path("image_fallback").asText(null);
                }
                if (image != null && !image.isBlank()) {
                    pages.add(new SourcePage(index++, image, headers));
                }
            }
        } catch (Exception e) {
            return pages;
        }
        return pages;
    }

    @Override
    public Map<String, String> coverRequestHeaders() {
        return Map.of("User-Agent", USER_AGENT, "Referer", BASE_URL + "/");
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private Document getHtml(String url) {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/")
            .get().build();
        try (Response res = http().newCall(req).execute()) {
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                return null;
            }
            return Jsoup.parse(body.string(), url);
        } catch (Exception e) {
            return null; // fail soft, per the SPI contract
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

    private static String lastSegment(String url) {
        String trimmed = url.replaceAll("/+$", "");
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim(), PUBLISHED);
        } catch (Exception e) {
            return null;
        }
    }
}
