package dev.kodex.plugin.novelbuddy;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NovelBuddy's browse/search filters, mirroring the upstream LNReader plugin. The UI edits
 * {@link Filter} state by <em>name</em>, so each filter keeps a parallel table of the values
 * {@code /titles/search} expects.
 */
final class Filters {

    private Filters() {
    }

    /** A filter option: the label the UI shows and the value the search API expects. */
    record Option(String label, String value) {
    }

    static final String ORDER_BY = "Order by";
    static final String STATUS = "Status";
    static final String GENRES = "Genres (OR, not AND)";
    static final String DEMOGRAPHICS = "Demographics";
    static final String MIN_CHAPTERS = "Minimum Chapters";
    static final String MAX_CHAPTERS = "Maximum Chapters";

    static final List<Option> ORDER_OPTIONS = List.of(
        new Option("Default Order", ""),
        new Option("Most Viewed", "views"),
        new Option("Latest Updated", "latest"),
        new Option("Most Popular", "popular"),
        new Option("A-Z", "alphabetical"),
        new Option("Highest Rating", "rating"),
        new Option("Most Chapters", "chapters"));

    static final List<Option> STATUS_OPTIONS = List.of(
        new Option("All", "all"),
        new Option("Ongoing", "ongoing"),
        new Option("Completed", "completed"),
        new Option("Hiatus", "hiatus"),
        new Option("Cancelled", "cancelled"));

    static final List<Option> GENRE_OPTIONS = List.of(
        new Option("Action", "action"),
        new Option("ActionAdventure", "actionadventure"),
        new Option("Adult", "adult"),
        new Option("Adventure", "adventure"),
        new Option("Comedy", "comedy"),
        new Option("Drama", "drama"),
        new Option("Eastern", "eastern"),
        new Option("Easterni", "easterni"),
        new Option("Ecchi", "ecchi"),
        new Option("Fan-Fiction", "fan-fiction"),
        new Option("Fantasy", "fantasy"),
        new Option("Game", "game"),
        new Option("Games", "games"),
        new Option("Gender Bender", "gender-bender"),
        new Option("Harem", "harem"),
        new Option("Historical", "historical"),
        new Option("Horror", "horror"),
        new Option("Isekai", "isekai"),
        new Option("Josei", "josei"),
        new Option("Lolicon", "lolicon"),
        new Option("Magic", "magic"),
        new Option("Martial Arts", "martial-arts"),
        new Option("Mature", "mature"),
        new Option("Mecha", "mecha"),
        new Option("Military", "military"),
        new Option("Modern Life", "modern-life"),
        new Option("Movies", "movies"),
        new Option("Mystery", "mystery"),
        new Option("Psychologic", "psychologic"),
        new Option("Psychological", "psychological"),
        new Option("Reincarnatio", "reincarnatio"),
        new Option("Reincarnation", "reincarnation"),
        new Option("Romanc", "romanc"),
        new Option("Romance", "romance"),
        new Option("Romance.Adventure", "romance-adventure"),
        new Option("RomanceAdventure", "romanceadventure"),
        new Option("Romance.Harem", "romance-harem"),
        new Option("RomanceHarem", "romanceharem"),
        new Option("Romance.Smut", "romance-smut"),
        new Option("Romancei", "romancei"),
        new Option("Romancem", "romancem"),
        new Option("School Life", "school-life"),
        new Option("Sci-fi", "sci-fi"),
        new Option("Seinen", "seinen"),
        new Option("Seinen Wuxia", "seinen-wuxia"),
        new Option("Shoujo", "shoujo"),
        new Option("Shoujo Ai", "shoujo-ai"),
        new Option("Shounen", "shounen"),
        new Option("Shounen Ai", "shounen-ai"),
        new Option("Slice of Lif", "slice-of-lif"),
        new Option("Slice Of Life", "slice-of-life"),
        new Option("Slice of Lifel", "slice-of-lifel"),
        new Option("Smut", "smut"),
        new Option("Sports", "sports"),
        new Option("Superna", "superna"),
        new Option("Supernatural", "supernatural"),
        new Option("System", "system"),
        new Option("Thriller", "thriller"),
        new Option("Tragedy", "tragedy"),
        new Option("Urban", "urban"),
        new Option("Urban Life", "urban-life"),
        new Option("Wuxia", "wuxia"),
        new Option("Xianxia", "xianxia"),
        new Option("Xuanhuan", "xuanhuan"),
        new Option("Yaoi", "yaoi"),
        new Option("Yuri", "yuri"));

    static final List<Option> DEMOGRAPHIC_OPTIONS = List.of(
        new Option("Shounen", "shounen"),
        new Option("Shoujo", "shoujo"),
        new Option("Seinen", "seinen"),
        new Option("Josei", "josei"));

    private static final Map<String, List<Option>> OPTIONS_BY_FILTER = Map.of(
        ORDER_BY, ORDER_OPTIONS,
        STATUS, STATUS_OPTIONS,
        GENRES, GENRE_OPTIONS,
        DEMOGRAPHICS, DEMOGRAPHIC_OPTIONS);

    /** {@code sort=views} is the upstream default, matching the site's own "Most Viewed" landing order. */
    static final String DEFAULT_ORDER = "views";

    static FilterList defaultFilterList(String order) {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(select(ORDER_BY, ORDER_OPTIONS, indexOfValue(ORDER_OPTIONS, order)));
        filters.add(select(STATUS, STATUS_OPTIONS, 0));
        filters.add(triStateGroup(GENRES, GENRE_OPTIONS));
        filters.add(checkBoxGroup(DEMOGRAPHICS, DEMOGRAPHIC_OPTIONS));
        filters.add(new Filter.TextFilter(MIN_CHAPTERS));
        filters.add(new Filter.TextFilter(MAX_CHAPTERS));
        return new FilterList(filters);
    }

    private static int indexOfValue(List<Option> options, String value) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).value().equals(value)) {
                return i;
            }
        }
        return 0;
    }

    static Filter.Select select(String name, List<Option> options, int state) {
        List<String> labels = new ArrayList<>(options.size());
        for (Option option : options) {
            labels.add(option.label());
        }
        return new Filter.Select(name, labels, state);
    }

    static Filter.Group triStateGroup(String name, List<Option> options) {
        List<Filter<?>> items = new ArrayList<>(options.size());
        for (Option option : options) {
            items.add(new Filter.TriState(option.label()));
        }
        return new Filter.Group(name, items);
    }

    static Filter.Group checkBoxGroup(String name, List<Option> options) {
        List<Filter<?>> items = new ArrayList<>(options.size());
        for (Option option : options) {
            items.add(new Filter.CheckBox(option.label()));
        }
        return new Filter.Group(name, items);
    }

    /** The query value behind an option label inside {@code group}, or {@code null} when unknown. */
    static String valueOf(String group, String label) {
        List<Option> options = OPTIONS_BY_FILTER.get(group);
        if (options == null) {
            return null;
        }
        for (Option option : options) {
            if (option.label().equals(label)) {
                return option.value();
            }
        }
        return null;
    }

    /** The value a {@link Filter.Select} points at, or {@code null} when it sits on the catch-all entry. */
    static String selectedValue(Filter.Select select) {
        List<Option> options = OPTIONS_BY_FILTER.get(select.name());
        if (options == null) {
            return null;
        }
        Integer state = select.state();
        int index = state == null ? 0 : state;
        if (index < 0 || index >= options.size()) {
            return null;
        }
        String value = options.get(index).value();
        return value.isEmpty() ? null : value;
    }
}
