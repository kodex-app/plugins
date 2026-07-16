package dev.kodex.ext.lnori;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.List;

final class Filters {

    private Filters() {
    }

    static final String SORT = "Sort By";
    static final String GENRE = "Genre";

    static final List<String> SORT_LABELS = List.of("Popular (Default)", "Title A-Z", "Title Z-A", "Year Released");
    static final List<String> SORT_VALUES = List.of("popular", "title-az", "title-za", "date");

    static final List<String> GENRE_LABELS = List.of(
        "All", "Academy", "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Harem", "Historical",
        "Isekai", "Magic", "Mystery", "Psychological", "Reincarnation", "Romance", "Sci-Fi", "Slice of Life",
        "Tragedy", "Female Protagonist", "Male Protagonist");
    static final List<String> GENRE_VALUES = List.of(
        "", "academy", "action", "adventure", "comedy", "drama", "fantasy", "harem", "historical",
        "isekai", "magic", "mystery", "psychological", "reincarnation", "romance", "sci-fi", "slice-of-life",
        "tragedy", "female protagonist", "male protagonist");

    static FilterList defaultFilterList() {
        return filterList(0);
    }

    /** Filter state pre-selected to the "Year Released" sort — drives the source's "Latest" feed. */
    static FilterList dateFilterList() {
        return filterList(Math.max(0, SORT_VALUES.indexOf("date")));
    }

    private static FilterList filterList(int sortIndex) {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Select(SORT, SORT_LABELS, sortIndex));
        filters.add(new Filter.Select(GENRE, GENRE_LABELS, 0));
        return new FilterList(filters);
    }

    /** The selected sort value ({@code popular} / {@code title-az} / {@code title-za}). */
    static String sortValue(FilterList filters) {
        return selectValue(filters, SORT, SORT_LABELS, SORT_VALUES, "popular");
    }

    /** The selected genre tag ({@code ""} = all). */
    static String genreValue(FilterList filters) {
        return selectValue(filters, GENRE, GENRE_LABELS, GENRE_VALUES, "");
    }

    private static String selectValue(FilterList filters, String name, List<String> labels, List<String> values, String def) {
        if (filters == null) {
            return def;
        }
        for (Filter<?> f : filters.filters()) {
            if (f instanceof Filter.Select s && name.equals(s.name())) {
                Integer state = s.state();
                if (state != null && state >= 0 && state < values.size()) {
                    return values.get(state);
                }
            }
        }
        return def;
    }
}
