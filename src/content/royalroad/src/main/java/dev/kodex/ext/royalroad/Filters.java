package dev.kodex.ext.royalroad;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;
import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.List;

final class Filters {

    private Filters() {
    }

    static final String SORT = "Order by";
    static final String STATUS = "Status";
    static final String TYPE = "Type";
    static final String GENRES = "Genres";

    // label → query value. Index in the Select maps to the value here.
    static final List<String> SORT_LABELS = List.of(
        "Relevance", "Popularity", "Average Rating", "Last Update", "Release Date", "Followers", "Pages", "Views", "Title");
    static final List<String> SORT_VALUES = List.of(
        "relevance", "popularity", "rating", "last_update", "release_date", "followers", "length", "views", "title");

    static final List<String> STATUS_LABELS = List.of("All", "Completed", "Ongoing", "Hiatus", "Dropped", "Stub");
    static final List<String> STATUS_VALUES = List.of("ALL", "COMPLETED", "ONGOING", "HIATUS", "DROPPED", "STUB");

    static final List<String> TYPE_LABELS = List.of("All", "Original", "Fan Fiction");
    static final List<String> TYPE_VALUES = List.of("ALL", "original", "fanfiction");

    // Royal Road genre tag slugs (the common subset used by /fictions/search&tagsAdd=).
    static final List<String> GENRE_LABELS = List.of(
        "Action", "Adventure", "Comedy", "Contemporary", "Drama", "Fantasy", "Historical", "Horror",
        "Mystery", "Psychological", "Romance", "Satire", "Sci-fi", "Short Story", "Tragedy",
        "LitRPG", "Magic", "Martial Arts", "Portal Fantasy / Isekai", "Progression", "Reincarnation",
        "Slice of Life", "Supernatural", "Wuxia", "Xianxia");
    static final List<String> GENRE_VALUES = List.of(
        "action", "adventure", "comedy", "contemporary", "drama", "fantasy", "historical", "horror",
        "mystery", "psychological", "romance", "satire", "sci_fi", "one_shot", "tragedy",
        "litrpg", "magic", "martial_arts", "summoned_hero", "progression", "reincarnation",
        "slice_of_life", "supernatural", "wuxia", "xianxia");

    static FilterList defaultFilterList() {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Select(SORT, SORT_LABELS, 0));
        filters.add(new Filter.Select(STATUS, STATUS_LABELS, 0));
        filters.add(new Filter.Select(TYPE, TYPE_LABELS, 0));
        List<Filter<?>> genres = new ArrayList<>();
        for (String label : GENRE_LABELS) {
            genres.add(new Filter.TriState(label));
        }
        filters.add(new Filter.Group(GENRES, genres));
        return new FilterList(filters);
    }

    /** Applies a (possibly user-edited) {@link FilterList}'s state to a {@code /fictions/search} query. */
    static void applyToUrl(HttpUrl.Builder builder, FilterList filters) {
        for (Filter<?> filter : filters.filters()) {
            switch (filter) {
                case Filter.Select select -> applySelect(builder, select);
                case Filter.Group group when GENRES.equals(group.name()) -> {
                    for (Filter<?> f : group.state()) {
                        if (f instanceof Filter.TriState ts) {
                            int idx = GENRE_LABELS.indexOf(ts.name());
                            if (idx < 0) {
                                continue;
                            }
                            if (ts.isIncluded()) {
                                builder.addQueryParameter("tagsAdd", GENRE_VALUES.get(idx));
                            } else if (ts.isExcluded()) {
                                builder.addQueryParameter("tagsRemove", GENRE_VALUES.get(idx));
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void applySelect(HttpUrl.Builder builder, Filter.Select select) {
        switch (select.name()) {
            case SORT -> {
                String value = valueAt(SORT_VALUES, select.state());
                if (value != null && !"relevance".equals(value)) {
                    builder.addQueryParameter("orderBy", value);
                }
            }
            case STATUS -> {
                String value = valueAt(STATUS_VALUES, select.state());
                if (value != null && !"ALL".equals(value)) {
                    builder.addQueryParameter("status", value);
                }
            }
            case TYPE -> {
                String value = valueAt(TYPE_VALUES, select.state());
                if (value != null && !"ALL".equals(value)) {
                    builder.addQueryParameter("type", value);
                }
            }
            default -> {
            }
        }
    }

    private static String valueAt(List<String> values, Integer state) {
        if (state == null || values.isEmpty()) {
            return null;
        }
        return values.get(Math.floorMod(state, values.size()));
    }
}
