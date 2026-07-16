package dev.kodex.ext.manhwazone;

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

    static final String SORT = "Sort By";
    static final String STATUS = "Status";
    static final String GENRES = "Genres";

    private static final Map<String, String> SORT_OPTIONS = ordered(
        "Popularity", "popularity", "Latest", "latest", "Rank", "rank", "Score", "score",
        "Follower", "follower", "A → Z", "name_asc", "Z → A", "name_desc");
    private static final Map<String, String> STATUS_OPTIONS = ordered(
        "All Status", "", "Finished", "finished", "On Hiatus", "on_hiatus",
        "On Going", "currently_publishing", "Discontinued", "discontinued");

    // label -> slug, declaration order preserved.
    private static final Map<String, String> GENRE_OPTIONS = ordered(
        "Action", "action", "Adventure", "adventure", "Avant Garde", "avant-garde",
        "Award Winning", "award-winning", "Boys Love", "boys-love", "Comedy", "comedy", "Drama", "drama",
        "Fantasy", "fantasy", "Girls Love", "girls-love", "Gourmet", "gourmet", "Horror", "horror",
        "Mystery", "mystery", "Romance", "romance", "Sci-Fi", "sci-fi", "Slice of Life", "slice-of-life",
        "Sports", "sports", "Supernatural", "supernatural", "Suspense", "suspense",
        "Urban Fantasy", "urban-fantasy", "Ecchi", "ecchi", "Erotica", "erotica", "Hentai", "hentai",
        "Isekai", "isekai", "Harem", "harem", "Historical", "historical", "Martial Arts", "martial-arts",
        "Mecha", "mecha", "Psychological", "psychological", "Reincarnation", "reincarnation",
        "Reverse Harem", "reverse-harem", "School", "school", "Seinen", "seinen", "Shoujo", "shoujo",
        "Shounen", "shounen", "Josei", "josei", "Vampire", "vampire", "Villainess", "villainess");

    static FilterList defaultFilterList() {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Select(SORT, List.copyOf(SORT_OPTIONS.keySet()), 0));
        filters.add(new Filter.Select(STATUS, List.copyOf(STATUS_OPTIONS.keySet()), 0));
        List<Filter<?>> genres = new ArrayList<>();
        for (String name : GENRE_OPTIONS.keySet()) {
            genres.add(new Filter.CheckBox(name));
        }
        filters.add(new Filter.Group(GENRES, genres));
        return new FilterList(filters);
    }

    static void applyToUrl(HttpUrl.Builder builder, FilterList filters) {
        if (filters == null) {
            return;
        }
        for (Filter<?> filter : filters.filters()) {
            switch (filter) {
                case Filter.Select select -> {
                    int idx = select.state() == null ? 0 : select.state();
                    if (SORT.equals(select.name())) {
                        applySelect(builder, "sortBy", SORT_OPTIONS, idx);
                    } else if (STATUS.equals(select.name())) {
                        applySelect(builder, "status", STATUS_OPTIONS, idx);
                    }
                }
                case Filter.Group group -> {
                    if (GENRES.equals(group.name())) {
                        List<String> slugs = new ArrayList<>();
                        for (Filter<?> child : group.state()) {
                            if (child instanceof Filter.CheckBox cb && Boolean.TRUE.equals(cb.state())) {
                                String slug = GENRE_OPTIONS.get(cb.name());
                                if (slug != null) {
                                    slugs.add(slug);
                                }
                            }
                        }
                        if (!slugs.isEmpty()) {
                            builder.addQueryParameter("genres", String.join("_", slugs));
                        }
                    }
                }
                default -> { }
            }
        }
    }

    private static void applySelect(HttpUrl.Builder builder, String key, Map<String, String> options, int idx) {
        List<String> values = List.copyOf(options.values());
        if (idx >= 0 && idx < values.size()) {
            String value = values.get(idx);
            if (!value.isEmpty()) {
                builder.addQueryParameter(key, value);
            }
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
