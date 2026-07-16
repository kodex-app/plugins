package dev.kodex.ext.weebcentral;

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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class WeebCentralSource implements ContentSource {

    private static final String BASE_URL = "https://weebcentral.com";
    private static final int FETCH_LIMIT = 32;
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /** Outbound HTTP via the core's proxy/DoH-configured client; a plain client only as a fallback. */
    private static final OkHttpClient FALLBACK = new OkHttpClient();
    private volatile HttpClientProvider httpProvider;

    @Override
    public void setHttpClientProvider(HttpClientProvider provider) {
        this.httpProvider = provider;
    }

    // Use the Cloudflare-aware client: weebcentral.com sits behind Cloudflare, so challenged requests are
    // solved via the operator's FlareSolverr/Byparr (a no-op falling back to the plain client when none is set).
    private OkHttpClient http() {
        HttpClientProvider p = httpProvider;
        return p != null ? p.cloudflareClient() : FALLBACK;
    }

    @Override
    public String displayName() {
        return "Weeb Central";
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
        return searchData(page, "", Filters.defaultFilterList("Popularity"));
    }

    @Override
    public SeriesPage latest(int page, ProviderSettings settings) {
        return searchData(page, "", Filters.defaultFilterList("Latest Updates"));
    }

    @Override
    public SeriesPage search(String query, int page, FilterList filters, ProviderSettings settings) {
        FilterList effective = (filters == null || filters.filters().isEmpty()) ? getFilterList() : filters;
        return searchData(page, query == null ? "" : query, effective);
    }

    @Override
    public FilterList getFilterList() {
        return Filters.defaultFilterList("");
    }

    private SeriesPage searchData(int page, String text, FilterList filters) {
        HttpUrl.Builder url = HttpUrl.get(BASE_URL + "/search/data").newBuilder();
        url.addQueryParameter("text", text.replaceAll("[!#:(),-]", " ").trim());
        Filters.applyToUrl(url, filters);
        url.addQueryParameter("limit", String.valueOf(FETCH_LIMIT));
        url.addQueryParameter("offset", String.valueOf((Math.max(1, page) - 1) * FETCH_LIMIT));
        url.addQueryParameter("display_mode", "Full Display");
        Document doc = getHtml(url.build().toString());
        if (doc == null) {
            return SeriesPage.empty();
        }
        List<SearchResult> items = new ArrayList<>();
        for (Element a : doc.select("article > section > a")) {
            Element titleEl = a.selectFirst("div:not([class]):last-child");
            if (titleEl == null) {
                continue;
            }
            items.add(new SearchResult(id(), relative(a.attr("abs:href")), titleEl.text(),
                null, sourceImg(a), null, null, List.of(), SeriesStatus.UNKNOWN, Map.of()));
        }
        boolean hasNextPage = doc.selectFirst("button") != null;
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
        var sections = doc.select("section[x-data] > section");
        String cover = null;
        String author = "";
        String genreText = "";
        String status = "";
        if (!sections.isEmpty()) {
            Element s0 = sections.get(0);
            cover = sourceImg(s0);
            author = joinText(s0, "ul > li:has(strong:contains(Author)) > span > a");
            genreText = joinText(s0, "ul > li:has(strong:contains(Tag),strong:contains(Type)) a");
            Element st = s0.selectFirst("ul > li:has(strong:contains(Status)) > a");
            status = st != null ? st.text() : "";
        }
        String title = seriesExternalId;
        String description = "";
        if (sections.size() > 1) {
            Element s1 = sections.get(1);
            Element h1 = s1.selectFirst("h1");
            if (h1 != null) {
                title = h1.text();
            }
            Element desc = s1.selectFirst("li:has(strong:contains(Description)) > p");
            if (desc != null) {
                description = desc.text();
            }
        }
        List<String> genres = genreText.isBlank() ? List.of()
            : Arrays.stream(genreText.split(",\\s*")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new SearchResult(id(), seriesExternalId, title, description.isBlank() ? null : description, cover,
            author.isBlank() ? null : author, null, genres, parseStatus(status), Map.of());
    }

    private static SeriesStatus parseStatus(String status) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "ongoing" -> SeriesStatus.ONGOING;
            case "complete" -> SeriesStatus.COMPLETED;
            case "hiatus" -> SeriesStatus.ON_HIATUS;
            case "canceled" -> SeriesStatus.CANCELLED;
            default -> SeriesStatus.UNKNOWN;
        };
    }

    // ---- Chapters --------------------------------------------------------------------------------

    @Override
    public List<SourceChapter> listChapters(String seriesExternalId, ProviderSettings settings) {
        Document doc = getHtml(BASE_URL + chapterListPath(seriesExternalId));
        List<SourceChapter> chapters = new ArrayList<>();
        if (doc == null) {
            return chapters;
        }
        for (Element a : doc.select("div[x-data] > a")) {
            Element nameEl = a.selectFirst("span.flex > span");
            String name = nameEl != null ? nameEl.text() : a.text();
            String href = relative(a.attr("abs:href"));
            LocalDate date = null;
            Element time = a.selectFirst("time[datetime]");
            if (time != null) {
                date = parseDate(time.attr("datetime"));
            }
            String scanlator = null;
            Element svg = a.selectFirst("svg");
            if (svg != null) {
                scanlator = switch (svg.attr("stroke")) {
                    case "#d8b4fe" -> "Official";
                    case "#4C4D54" -> "Unknown";
                    default -> null;
                };
            }
            chapters.add(new SourceChapter(href, name, parseNumber(name), scanlator, date, Map.of()));
        }
        return chapters;
    }

    /** {@code /series/<id>/<slug>} → {@code /series/<id>/full-chapter-list}. */
    private static String chapterListPath(String seriesExternalId) {
        String[] segs = seriesExternalId.replaceAll("^/+", "").split("/");
        if (segs.length >= 2) {
            return "/" + segs[0] + "/" + segs[1] + "/full-chapter-list";
        }
        return seriesExternalId;
    }

    // ---- Pages -----------------------------------------------------------------------------------

    @Override
    public List<SourcePage> pageList(String chapterExternalId, ProviderSettings settings) {
        String url = BASE_URL + chapterExternalId + "/images?is_prev=False&reading_style=long_strip";
        Document doc = getHtml(url);
        List<SourcePage> pages = new ArrayList<>();
        if (doc == null) {
            return pages;
        }
        var imgs = doc.select("section[x-data~=scroll] > img");
        Map<String, String> headers = Map.of(
            "Referer", BASE_URL + "/",
            "Accept", "image/avif,image/webp,*/*",
            "User-Agent", USER_AGENT);
        for (int i = 0; i < imgs.size(); i++) {
            String src = imgs.get(i).attr("abs:src");
            if (!src.isBlank()) {
                pages.add(new SourcePage(i, src, headers));
            }
        }
        return pages;
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

    /** Cover image: prefer the {@code <source srcset>} (upgraded small→normal), else {@code <img src>}. */
    private static String sourceImg(Element el) {
        Element source = el.selectFirst("source");
        if (source != null && !source.attr("srcset").isBlank()) {
            return source.attr("srcset").replace("small", "normal");
        }
        Element img = el.selectFirst("img");
        return img != null ? img.attr("abs:src") : null;
    }

    private static String joinText(Element root, String selector) {
        return String.join(", ", root.select(selector).eachText());
    }

    /** Strips scheme+host from an absolute URL, keeping the path (+ query). */
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

    private static LocalDate parseDate(String iso) {
        try {
            return OffsetDateTime.parse(iso).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }
}
