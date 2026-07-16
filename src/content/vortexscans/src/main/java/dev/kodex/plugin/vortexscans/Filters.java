package dev.kodex.plugin.vortexscans;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;
import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Filters {

    private Filters() {
    }

    static final String STATUS = "Status";
    static final String TYPE = "Type";
    static final String SORT = "Sort by";
    static final String SORT_DIRECTION = "Sort direction";
    static final String GENRES = "Genres";

    // label -> API value, declaration order preserved (the Select index addresses these).
    private static final Map<String, String> STATUS_OPTIONS = ordered(
        "All", "", "Ongoing", "ONGOING", "Completed", "COMPLETED", "Cancelled", "CANCELLED",
        "Dropped", "DROPPED", "Coming soon", "COMING_SOON", "Mass released", "MASS_RELEASED");
    private static final Map<String, String> TYPE_OPTIONS = ordered(
        "All", "", "Manga", "MANGA", "Manhua", "MANHUA", "Manhwa", "MANHWA");
    private static final Map<String, String> SORT_OPTIONS = ordered(
        "Last chapter added", "lastChapterAddedAt", "Views", "totalViews", "Created at", "createdAt",
        "Chapter count", "chaptersCount", "Alphabetical", "postTitle");
    private static final Map<String, String> SORT_DIRECTION_OPTIONS = ordered(
        "Descending", "desc", "Ascending", "asc");

    // name -> genre id, used by the tri-state genre group.
    private static final Map<String, String> GENRE_OPTIONS = ordered(
        "Action", "1", "Adventure", "13", "Comedy", "7", "Drama", "2", "Fantasy", "8", "Gore", "41",
        "Harem", "49", "Historical", "19", "Horror", "9", "Isekai", "42", "Josei", "21",
        "Martial Arts", "6", "Mature", "12", "Murim", "31", "Mystery", "50", "Psychological", "62",
        "Reincarnation", "16", "Revenge", "17", "Romance", "20", "School Life", "23", "Sci-Fi", "40",
        "Seinen", "10", "Shoujo", "22", "Shounen", "3", "Slice Of Life", "18", "Sports", "4",
        "Supernatural", "11", "System", "15", "Tragedy", "63", "Video Games", "27", "Webtoon", "33");

    private static final String GENRE_INCLUDE_KEY = "genreIds";
    private static final String GENRE_EXCLUDE_KEY = "excludedGenreIds";

    static FilterList defaultFilterList() {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Select(STATUS, List.copyOf(STATUS_OPTIONS.keySet()), 0));
        filters.add(new Filter.Select(TYPE, List.copyOf(TYPE_OPTIONS.keySet()), 0));
        filters.add(new Filter.Select(SORT, List.copyOf(SORT_OPTIONS.keySet()), 0));
        filters.add(new Filter.Select(SORT_DIRECTION, List.copyOf(SORT_DIRECTION_OPTIONS.keySet()), 0));
        List<Filter<?>> genres = new ArrayList<>();
        for (String name : GENRE_OPTIONS.keySet()) {
            genres.add(new Filter.TriState(name));
        }
        filters.add(new Filter.Group(GENRES, genres));
        return new FilterList(filters);
    }

    /** Applies a (possibly user-edited) {@link FilterList} to a {@code /api/query} URL. */
    static void applyToUrl(HttpUrl.Builder builder, FilterList filters) {
        for (Filter<?> filter : filters.filters()) {
            switch (filter) {
                case Filter.Select select -> applySelect(builder, select);
                case Filter.Group group -> {
                    if (GENRES.equals(group.name())) {
                        applyGenres(builder, group);
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void applySelect(HttpUrl.Builder builder, Filter.Select select) {
        Map<String, String> options = switch (select.name()) {
            case STATUS -> STATUS_OPTIONS;
            case TYPE -> TYPE_OPTIONS;
            case SORT -> SORT_OPTIONS;
            case SORT_DIRECTION -> SORT_DIRECTION_OPTIONS;
            default -> null;
        };
        if (options == null || options.isEmpty()) {
            return;
        }
        List<String> values = List.copyOf(options.values());
        int idx = Math.floorMod(select.state(), values.size());
        String value = values.get(idx);
        if (value.isBlank()) {
            return;
        }
        String key = switch (select.name()) {
            case STATUS -> "seriesStatus";
            case TYPE -> "seriesType";
            case SORT -> "orderBy";
            case SORT_DIRECTION -> "orderDirection";
            default -> null;
        };
        if (key != null) {
            builder.setQueryParameter(key, value);
        }
    }

    private static void applyGenres(HttpUrl.Builder builder, Filter.Group group) {
        List<String> included = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (Filter<?> f : group.state()) {
            if (f instanceof Filter.TriState ts) {
                String genreId = GENRE_OPTIONS.get(ts.name());
                if (genreId == null) {
                    continue;
                }
                if (ts.isIncluded()) {
                    included.add(genreId);
                } else if (ts.isExcluded()) {
                    excluded.add(genreId);
                }
            }
        }
        if (!included.isEmpty()) {
            builder.addQueryParameter(GENRE_INCLUDE_KEY, String.join(",", included));
        }
        if (!excluded.isEmpty()) {
            builder.addQueryParameter(GENRE_EXCLUDE_KEY, String.join(",", excluded));
        }
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
