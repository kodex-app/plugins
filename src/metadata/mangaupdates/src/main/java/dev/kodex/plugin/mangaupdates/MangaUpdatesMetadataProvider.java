package dev.kodex.plugin.mangaupdates;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.kodex.spi.ProviderSettings;
import dev.kodex.spi.common.http.HttpClientProvider;
import dev.kodex.spi.metadata.MetadataCapability;
import dev.kodex.spi.metadata.MetadataProvider;
import dev.kodex.spi.metadata.MetadataTarget;
import dev.kodex.spi.metadata.SeriesContext;
import dev.kodex.spi.metadata.SeriesMetadataPatch;
import dev.kodex.spi.common.model.WebLink;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.pf4j.Extension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Extension
public class MangaUpdatesMetadataProvider implements MetadataProvider {

    private static final String SEARCH_URL = "https://api.mangaupdates.com/v1/series/search";
    private static final String SERIES_URL = "https://api.mangaupdates.com/v1/series/";
    private static final int MAX_TAGS = 15;
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern PAREN = Pattern.compile("\\((.*?)\\)");
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
        return "mangaupdates";
    }

    @Override
    public String displayName() {
        return "MangaUpdates";
    }

    @Override
    public Set<MetadataCapability> capabilities() {
        return Set.of(MetadataCapability.TITLE, MetadataCapability.SUMMARY, MetadataCapability.PUBLISHER,
            MetadataCapability.GENRES, MetadataCapability.TAGS, MetadataCapability.LINKS);
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
        Long seriesId = search(name);
        if (seriesId == null) {
            return Optional.empty();
        }
        JsonNode series = getJson(SERIES_URL + seriesId, null);
        if (series == null || !series.isObject()) {
            return Optional.empty();
        }
        return Optional.of(toPatch(series));
    }

    /** Finds the best-matching series id for a name (exact title match wins, else the first hit). */
    private Long search(String name) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("search", name);
        body.put("perpage", 5);
        JsonNode res = getJson(SEARCH_URL, body.toString());
        if (res == null) {
            return null;
        }
        Long firstId = null;
        for (JsonNode hit : res.path("results")) {
            JsonNode record = hit.path("record");
            long id = record.path("series_id").asLong(0);
            if (id == 0) {
                continue;
            }
            if (firstId == null) {
                firstId = id;
            }
            String title = Parser.unescapeEntities(record.path("title").asText(""), false);
            if (title.equalsIgnoreCase(name)) {
                return id;
            }
        }
        return firstId;
    }

    private SeriesMetadataPatch toPatch(JsonNode series) {
        SeriesMetadataPatch.Builder b = SeriesMetadataPatch.builder();

        String title = text(series.get("title"));
        if (title != null) {
            b.title(Parser.unescapeEntities(title, false).replaceAll("\\s*\\(Novel\\)$", ""));
        }

        String description = text(series.get("description"));
        if (description != null && !description.isBlank()) {
            b.summary(stripHtml(description));
        }

        String status = mapStatus(text(series.get("status")));
        if (status != null) {
            b.status(status);
        }

        publisher(series).ifPresent(b::publisher);

        List<String> genres = new ArrayList<>();
        for (JsonNode g : series.path("genres")) {
            String genre = text(g.get("genre"));
            if (genre != null && !genre.isBlank()) {
                genres.add(Parser.unescapeEntities(genre, false));
            }
        }
        if (!genres.isEmpty()) {
            b.genres(genres);
        }

        List<JsonNode> categories = new ArrayList<>();
        series.path("categories").forEach(categories::add);
        List<String> tags = categories.stream()
            .sorted(Comparator.comparingInt((JsonNode c) -> c.path("votes").asInt(0)).reversed())
            .limit(MAX_TAGS)
            .map(c -> text(c.get("category")))
            .filter(s -> s != null && !s.isBlank())
            .map(s -> Parser.unescapeEntities(s, false))
            .toList();
        if (!tags.isEmpty()) {
            b.tags(tags);
        }

        String url = text(series.get("url"));
        if (url != null && !url.isBlank()) {
            b.links(List.of(new WebLink("MangaUpdates", url)));
        }

        return b.build();
    }

    /** Original publisher first (the canonical one), else any publisher. */
    private static Optional<String> publisher(JsonNode series) {
        String firstAny = null;
        for (JsonNode p : series.path("publishers")) {
            String pubName = text(p.get("publisher_name"));
            if (pubName == null || pubName.isBlank()) {
                continue;
            }
            pubName = Parser.unescapeEntities(pubName, false);
            if (firstAny == null) {
                firstAny = pubName;
            }
            if ("Original".equalsIgnoreCase(text(p.get("type")))) {
                return Optional.of(pubName);
            }
        }
        return Optional.ofNullable(firstAny);
    }

    /**
     * MangaUpdates reports status as free text with parenthetical labels (e.g. "5 Volumes (Complete)").
     * Mirroring komf: take the parenthetical groups and only map when they all agree; else leave it.
     */
    private static String mapStatus(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = PAREN.matcher(raw);
        List<String> groups = new ArrayList<>();
        while (m.find()) {
            groups.add(m.group(1).trim());
        }
        if (groups.isEmpty()) {
            return null;
        }
        String first = groups.get(0).toLowerCase(Locale.ROOT);
        if (!groups.stream().allMatch(g -> g.equalsIgnoreCase(groups.get(0)))) {
            return null;
        }
        if (first.contains("complete")) return "ENDED";
        if (first.contains("ongoing")) return "ONGOING";
        if (first.contains("hiatus")) return "HIATUS";
        if (first.contains("cancel") || first.contains("discontin")) return "ABANDONED";
        return null;
    }

    /** Converts the description's lightweight HTML to plain text (line breaks preserved, entities decoded). */
    private static String stripHtml(String html) {
        String withBreaks = html.replaceAll("(?i)<br\\s*/?>", "\n");
        return Jsoup.parse(withBreaks).wholeText().strip();
    }

    /** A scalar JSON node's text, or null when the field is absent/null/non-scalar. */
    private static String text(JsonNode node) {
        return node != null && !node.isNull() && node.isValueNode() ? node.asText() : null;
    }

    /** GET ({@code body == null}) or POST a JSON request and parse the response; fails soft (null). */
    private JsonNode getJson(String url, String body) {
        Request.Builder req = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json");
        req = body == null ? req.get() : req.post(RequestBody.create(body, JSON));
        try (Response res = http().newCall(req.build()).execute()) {
            ResponseBody responseBody = res.body();
            if (!res.isSuccessful() || responseBody == null) {
                return null;
            }
            return MAPPER.readTree(responseBody.string());
        } catch (Exception e) {
            return null; // fail soft, per the SPI contract
        }
    }
}
