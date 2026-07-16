package dev.kodex.ext.amazon;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.kodex.spi.PluginConfigSchema;
import dev.kodex.spi.PluginConfigSchema.Field;
import dev.kodex.spi.PluginConfigSchema.FieldType;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.common.http.ProviderRateLimitException;
import dev.kodex.spi.metadata.MetadataCapability;
import dev.kodex.spi.metadata.MetadataIdentifiers;
import dev.kodex.spi.metadata.MetadataProvider;
import dev.kodex.spi.metadata.MetadataTarget;
import dev.kodex.spi.metadata.SeriesContext;
import dev.kodex.spi.metadata.SeriesMetadataPatch;
import dev.kodex.spi.metadata.TitleMatch;
import dev.kodex.spi.common.model.WebLink;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class AmazonMetadataProvider implements MetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(AmazonMetadataProvider.class);

    private static final String DEFAULT_DOMAIN = "amazon.com";
    private static final String USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/137.0.0.0 Safari/537.36";
    // Storefront cards that aren't a single edition we'd want to map.
    private static final Pattern SKIP_TITLE = Pattern.compile(
        "box\\s*set|collection\\s*set|books\\s*set|omnibus|summary\\s*&\\s*study|streamer|display\\s*kit"
            + "|bookstore\\s*kit|shelf\\s*kit", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASIN = Pattern.compile("/dp/([A-Z0-9]{10})", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("\\b(1[0-9]{3}|2[0-9]{3})\\b");
    // Lower index = preferred edition format when a card lists several.
    private static final List<String> FORMAT_PREFERENCE =
        List.of("kindle", "paperback", "mass market paperback", "hardcover", "library binding");

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
    public String id() {
        return "amazon";
    }

    @Override
    public String displayName() {
        return "Amazon";
    }

    @Override
    public PluginConfigSchema configSchema() {
        return new PluginConfigSchema(List.of(
            new Field("domain", "Storefront domain", FieldType.STRING, false, DEFAULT_DOMAIN, List.of()),
            Field.secret("cookie", "Session cookie (optional)", false)));
    }

    @Override
    public Set<MetadataCapability> capabilities() {
        return Set.of(MetadataCapability.TITLE, MetadataCapability.SUMMARY, MetadataCapability.PUBLISHER,
            MetadataCapability.LANGUAGE, MetadataCapability.GENRES, MetadataCapability.LINKS,
            MetadataCapability.IDENTIFIERS, MetadataCapability.THUMBNAIL);
    }

    @Override
    public boolean supports(MetadataTarget target) {
        return target == MetadataTarget.SERIES || target == MetadataTarget.ONESHOT;
    }

    @Override
    public Optional<SeriesMetadataPatch> seriesMetadata(SeriesContext context, ProviderSettings settings) {
        String name = context.name();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String domain = settings.getString("domain", DEFAULT_DOMAIN);
        String cookie = settings.getString("cookie").orElse(null);

        // ID-first: when an ASIN is already known, fetch its product page directly (no storefront search).
        String knownAsin = context.identifiers().get(MetadataIdentifiers.AMAZON);
        String asin = knownAsin != null && !knownAsin.isBlank() ? knownAsin.trim() : firstAsin(name, domain, cookie);
        if (asin == null) {
            log.info("Amazon: no product found for '{}' on {}", name, domain);
            return Optional.empty();
        }
        if (asin.equals(knownAsin == null ? null : knownAsin.trim())) {
            log.info("Amazon: direct ASIN lookup {} for series '{}'", asin, name);
        }
        HttpUrl product = HttpUrl.get("https://www." + domain + "/dp/" + asin);
        Document doc = getHtml(product, cookie);
        if (doc == null) {
            return Optional.empty();
        }
        String title = title(doc);
        if (title == null) {
            log.info("Amazon: product {} had no title (layout changed or bot wall)", asin);
            return Optional.empty();
        }
        log.info("Amazon: matched ASIN {} ('{}') for series '{}'", asin, title, name);
        return Optional.of(toPatch(doc, product.toString(), asin));
    }

    /** Runs the storefront books search and returns the best ASIN, preferring known edition formats. */
    private String firstAsin(String query, String domain, String cookie) {
        HttpUrl search = HttpUrl.get("https://www." + domain + "/s").newBuilder()
            .addQueryParameter("k", query)
            .addQueryParameter("i", "stripbooks")
            .build();
        Document doc = getHtml(search, cookie);
        if (doc == null) {
            return null;
        }
        for (Element card : doc.select("div[data-component-type=s-search-result]")) {
            String cardTitle = card.select("[data-cy=title-recipe]").text();
            if (SKIP_TITLE.matcher(cardTitle).find()) {
                continue;
            }
            // Title-match threshold: storefront search pads results with loosely-related books; an
            // unrelated card must not become the series' metadata. An empty title (layout drift) is
            // let through — the product page itself is checked again by the caller.
            if (!cardTitle.isBlank() && !TitleMatch.acceptable(query, cardTitle)) {
                continue;
            }
            String chosen = chooseFormat(card);
            if (chosen == null) {
                String dataAsin = card.attr("data-asin");
                if (dataAsin.length() == 10) {
                    chosen = dataAsin;
                }
            }
            if (chosen != null) {
                return chosen;
            }
        }
        return null;
    }

    /** Among the /dp/ links in a result card, pick the ASIN of the highest-priority format. */
    private static String chooseFormat(Element card) {
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        Set<String> seen = new LinkedHashSet<>();
        for (Element a : card.select("a[href*=/dp/]")) {
            Matcher m = ASIN.matcher(a.attr("href"));
            if (!m.find()) {
                continue;
            }
            String asin = m.group(1);
            if (!seen.add(asin)) {
                continue;
            }
            String label = a.text().toLowerCase();
            int rank = FORMAT_PREFERENCE.size();
            for (int i = 0; i < FORMAT_PREFERENCE.size(); i++) {
                if (label.contains(FORMAT_PREFERENCE.get(i))) {
                    rank = i;
                    break;
                }
            }
            if (best == null || rank < bestRank) {
                best = asin;
                bestRank = rank;
            }
        }
        return best;
    }

    private static SeriesMetadataPatch toPatch(Document doc, String url, String asin) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();

        b.identifiers(Map.of(MetadataIdentifiers.AMAZON, asin));
        b.title(title(doc));

        String description = text(doc.selectFirst("#bookDescription_feature_div .a-expander-content"));
        if (description == null) {
            Element noscript = doc.selectFirst("#bookDescription_feature_div noscript");
            if (noscript != null) {
                description = Jsoup.parse(noscript.html()).wholeText().strip();
            }
        }
        if (description != null && !description.isBlank()) {
            b.summary(description.strip());
        }

        String publisher = detailValue(doc,
            "#rpi-attribute-book_details-publisher .rpi-attribute-value span", "publisher");
        if (publisher != null) {
            b.publisher(publisher.replaceAll("\\s*\\([^)]*\\)\\s*$", "").strip());
        }

        String language = detailValue(doc, "#rpi-attribute-language .rpi-attribute-value span", "language");
        if (language != null && !language.isBlank()) {
            b.language(language);
        }

        List<String> genres = categories(doc);
        if (!genres.isEmpty()) {
            b.genres(genres);
        }

        String cover = coverUrl(doc);
        if (cover != null) {
            b.coverUrl(cover);
        }

        b.links(List.of(new WebLink("Amazon", url)));
        return b.build();
    }

    /** Product title, with the subtitle (after a colon) appended, mirroring the bookorbit scraper. */
    private static String title(Document doc) {
        String raw = text(doc.selectFirst("#productTitle, #ebooksProductTitle"));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int colon = raw.indexOf(':');
        if (colon > 0) {
            return raw.substring(0, colon).strip() + ": " + raw.substring(colon + 1).strip();
        }
        return raw;
    }

    /** Bestseller-rank categories, falling back to the breadcrumb trail. */
    private static List<String> categories(Document doc) {
        Set<String> out = new LinkedHashSet<>();
        for (Element a : doc.select("#detailBullets_feature_div .zg_hrsr .a-list-item a")) {
            addCategory(out, a.text());
        }
        if (out.isEmpty()) {
            for (Element a : doc.select(
                "#wayfinding-breadcrumbs_feature_div li:not(.a-breadcrumb-divider) a")) {
                addCategory(out, a.text());
            }
        }
        return new ArrayList<>(out);
    }

    private static void addCategory(Set<String> out, String raw) {
        String name = raw.strip().replaceAll("\\s*\\(Books\\)\\s*$", "").strip();
        if (!name.isBlank()) {
            out.add(name);
        }
    }

    /**
     * Reads a value from the newer {@code rpi-attribute} panel via CSS selector, falling back to the
     * older "detail bullets" list keyed by a bold label substring.
     */
    private static String detailValue(Document doc, String rpiSelector, String bulletLabel) {
        String rpi = text(doc.selectFirst(rpiSelector));
        if (rpi != null && !rpi.isBlank()) {
            return rpi;
        }
        for (Element label : doc.select("#detailBullets_feature_div .a-text-bold")) {
            if (label.text().toLowerCase().contains(bulletLabel)) {
                Element value = label.nextElementSibling();
                return value == null ? null : value.text().strip();
            }
        }
        return null;
    }

    /** Largest available cover: prefer the dynamic-image map, then the hi-res / src attributes. */
    private static String coverUrl(Document doc) {
        Element img = doc.selectFirst("#landingImage, #imgBlkFront");
        if (img == null) {
            return null;
        }
        String dynamic = img.attr("data-a-dynamic-image");
        if (!dynamic.isBlank()) {
            try {
                JsonNode map = MAPPER.readTree(dynamic);
                if (map.isObject() && !map.properties().isEmpty()) {
                    // All entries are the same image at different sizes; strip the size modifier
                    // (e.g. "._SY342_.") to recover the full-resolution original.
                    String sample = map.properties().iterator().next().getKey();
                    return sample.replaceFirst("\\._[^.]+_\\.", ".");
                }
            } catch (Exception ignored) {
                // fall through to the static attributes
            }
        }
        String hires = img.attr("data-old-hires");
        if (!hires.isBlank()) {
            return hires;
        }
        String src = img.attr("src");
        return src.isBlank() ? null : src.replaceFirst("(?i)\\._[A-Z0-9_,]+_\\.", ".");
    }

    /** Trimmed text of an element, or null when the element is absent. */
    private static String text(Element el) {
        if (el == null) {
            return null;
        }
        String t = el.text().strip();
        return t.isEmpty() ? null : t;
    }

    private Document getHtml(HttpUrl url, String cookie) {
        // Full Chrome fingerprint (mirrors the bookorbit scraper): Amazon's WAF answers a Chrome
        // User-Agent that is missing the sec-ch-ua/sec-fetch client hints with a 503 bot wall.
        Request.Builder req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("sec-ch-ua", "\"Google Chrome\";v=\"137\", \"Chromium\";v=\"137\", \"Not_A Brand\";v=\"24\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"macOS\"")
            .header("sec-fetch-dest", "document")
            .header("sec-fetch-mode", "navigate")
            .header("sec-fetch-site", "none");
        if (cookie != null && !cookie.isBlank()) {
            req.header("Cookie", cookie);
        }
        try (Response res = http().newCall(req.get().build()).execute()) {
            if (res.code() == 429 || res.code() == 503) {
                // Amazon signals "stop scraping" as a 503 bot wall (rarely a plain 429). Let the core
                // cool this provider down instead of re-tripping the WAF on every series in the queue.
                throw new ProviderRateLimitException(
                    "Amazon bot wall / rate limit (HTTP " + res.code() + ")", retryAfterSeconds(res));
            }
            ResponseBody body = res.body();
            if (!res.isSuccessful() || body == null) {
                log.warn("Amazon: HTTP {} for {}", res.code(), url.encodedPath());
                return null;
            }
            return Jsoup.parse(body.string(), url.toString());
        } catch (ProviderRateLimitException e) {
            throw e; // must reach the core's cooldown handling — don't swallow with the IO failures below
        } catch (Exception e) {
            log.warn("Amazon: request failed for {}", url.encodedPath(), e);
            return null; // fail soft, per the SPI contract
        }
    }

    /** Parses a numeric {@code Retry-After} header; null when absent or not a number (date form). */
    private static Long retryAfterSeconds(Response res) {
        String header = res.header("Retry-After");
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
