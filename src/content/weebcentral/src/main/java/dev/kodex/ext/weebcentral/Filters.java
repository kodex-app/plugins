package dev.kodex.ext.weebcentral;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;
import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.List;

final class Filters {

    private Filters() {
    }

    static final List<String> SORT_VALUES = List.of(
        "Best Match", "Alphabet", "Popularity", "Subscribers", "Recently Added", "Latest Updates");
    static final List<String> ORDER_VALUES = List.of("Descending", "Ascending");
    static final List<String> ANY_TRUE_FALSE = List.of("Any", "True", "False");
    static final List<String> STATUS_VALUES = List.of("Ongoing", "Complete", "Hiatus", "Canceled");
    static final List<String> TYPE_VALUES = List.of("Manga", "Manhwa", "Manhua", "OEL");
    static final List<String> TAG_VALUES = List.of(
        "Action", "Adult", "Adventure", "Comedy", "Doujinshi", "Drama", "Ecchi", "Fantasy", "Gender Bender",
        "Harem", "Hentai", "Historical", "Horror", "Isekai", "Josei", "Lolicon", "Martial Arts", "Mature",
        "Mecha", "Mystery", "Psychological", "Romance", "School Life", "Sci-fi", "Seinen", "Shotacon", "Shoujo",
        "Shoujo Ai", "Shounen", "Shounen Ai", "Slice of Life", "Smut", "Sports", "Supernatural", "Tragedy",
        "Yaoi", "Yuri", "Other");

    static final String SORT = "Sort";
    static final String SORT_ORDER = "Sort Order";
    static final String OFFICIAL_TRANSLATION = "Official Translation";
    static final String ANIME_ADAPTATION = "Anime Adaptation";
    static final String ADULT_CONTENT = "Adult Content";
    static final String AUTHOR = "Author (Case-sensitive)";
    static final String SERIES_STATUS = "Series Status";
    static final String SERIES_TYPE = "Series Type";
    static final String TAGS = "Tags";

    /**
     * Mirrors {@code defaultFilterList(SortFilter(defaultSort))}. {@code defaultSort} selects the
     * initial Sort value (e.g. {@code "Popularity"} for popular, {@code "Latest Updates"} for latest,
     * {@code ""} for the user-facing filter list, which falls back to "Best Match").
     */
    static FilterList defaultFilterList(String defaultSort) {
        int sortIndex = Math.max(0, SORT_VALUES.indexOf(defaultSort));
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Select(SORT, SORT_VALUES, sortIndex));
        filters.add(new Filter.Select(SORT_ORDER, ORDER_VALUES, 0));
        filters.add(new Filter.Select(OFFICIAL_TRANSLATION, ANY_TRUE_FALSE, 0));
        filters.add(new Filter.Select(ANIME_ADAPTATION, ANY_TRUE_FALSE, 0));
        filters.add(new Filter.Select(ADULT_CONTENT, ANY_TRUE_FALSE, 0));
        filters.add(new Filter.TextFilter(AUTHOR));
        filters.add(checkboxGroup(SERIES_STATUS, STATUS_VALUES));
        filters.add(checkboxGroup(SERIES_TYPE, TYPE_VALUES));
        filters.add(triStateGroup(TAGS, TAG_VALUES));
        return new FilterList(filters);
    }

    private static Filter.Group checkboxGroup(String name, List<String> values) {
        List<Filter<?>> options = new ArrayList<>();
        for (String v : values) {
            options.add(new Filter.CheckBox(v));
        }
        return new Filter.Group(name, options);
    }

    private static Filter.Group triStateGroup(String name, List<String> values) {
        List<Filter<?>> options = new ArrayList<>();
        for (String v : values) {
            options.add(new Filter.TriState(v));
        }
        return new Filter.Group(name, options);
    }

    /** Applies a (possibly user-edited) {@link FilterList}'s state to a {@code /search/data} query. */
    static void applyToUrl(HttpUrl.Builder builder, FilterList filters) {
        for (Filter<?> filter : filters.filters()) {
            switch (filter) {
                case Filter.Select select -> applySelect(builder, select);
                case Filter.TextFilter text -> {
                    if (AUTHOR.equals(text.name()) && !text.state().isEmpty()) {
                        builder.addQueryParameter("author", text.state());
                    }
                }
                case Filter.Group group -> applyGroup(builder, group);
                default -> {
                }
            }
        }
    }

    private static void applySelect(HttpUrl.Builder builder, Filter.Select select) {
        List<String> values = select.values();
        if (values.isEmpty()) {
            return;
        }
        int idx = Math.floorMod(select.state(), values.size());
        String value = values.get(idx);
        switch (select.name()) {
            case SORT -> builder.addQueryParameter("sort", value);
            case SORT_ORDER -> builder.addQueryParameter("order", value);
            case OFFICIAL_TRANSLATION -> builder.addQueryParameter("official", value);
            case ANIME_ADAPTATION -> builder.addQueryParameter("anime", value);
            case ADULT_CONTENT -> builder.addQueryParameter("adult", value);
            default -> {
            }
        }
    }

    private static void applyGroup(HttpUrl.Builder builder, Filter.Group group) {
        switch (group.name()) {
            case SERIES_STATUS -> {
                for (Filter<?> f : group.state()) {
                    if (f instanceof Filter.CheckBox cb && cb.state()) {
                        builder.addQueryParameter("included_status", cb.name());
                    }
                }
            }
            case SERIES_TYPE -> {
                for (Filter<?> f : group.state()) {
                    if (f instanceof Filter.CheckBox cb && cb.state()) {
                        builder.addQueryParameter("included_type", cb.name());
                    }
                }
            }
            case TAGS -> {
                for (Filter<?> f : group.state()) {
                    if (f instanceof Filter.TriState ts) {
                        if (ts.isIncluded()) {
                            builder.addQueryParameter("included_tag", ts.name());
                        } else if (ts.isExcluded()) {
                            builder.addQueryParameter("excluded_tag", ts.name());
                        }
                    }
                }
            }
            default -> {
            }
        }
    }
}
