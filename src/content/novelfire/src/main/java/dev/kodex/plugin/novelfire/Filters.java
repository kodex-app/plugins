package dev.kodex.plugin.novelfire;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Novel Fire's advanced-search filters, mirroring the upstream LNReader filter definitions. The UI edits
 * {@link Filter} state by <em>name</em>, so each filter keeps a parallel table of the values
 * {@code /search-adv} expects.
 */
final class Filters {

    private Filters() {
    }

    /** A filter option: the label the UI shows and the value the search form expects. */
    record Option(String label, String value) {
    }

    static final String LANGUAGE = "Language";
    static final String GENRE_OPERATOR = "Genres (And/Or/Exclude)";
    static final String GENRES = "Genres";
    static final String CHAPTERS = "Chapters";
    static final String RATING_OPERATOR = "Rating (Min/Max)";
    static final String RATING = "Rating";
    static final String STATUS = "Translation Status";
    static final String SORT = "Sort Results By";
    static final String TAG_OPERATOR = "Tags (And/Or)";
    static final String AUTHOR = "Author";

    /** Sort key used for the popular feed; the latest feed overrides it with "date". */
    static final String DEFAULT_SORT = "rank-top";
    static final String LATEST_SORT = "date";

    static final List<Option> LANGUAGE_OPTIONS = List.of(
        new Option("Chinese Novel", "1"),
        new Option("Japanese Novel", "3"),
        new Option("English Novel", "4"));

    static final List<Option> GENRE_OPTIONS = List.of(
        new Option("Action", "3"),
        new Option("Adult", "28"),
        new Option("Adventure", "4"),
        new Option("Anime", "46"),
        new Option("Arts", "47"),
        new Option("Comedy", "5"),
        new Option("Drama", "24"),
        new Option("Eastern", "44"),
        new Option("Ecchi", "26"),
        new Option("Fan-fiction", "48"),
        new Option("Fantasy", "6"),
        new Option("Game", "19"),
        new Option("Gender Bender", "25"),
        new Option("Harem", "7"),
        new Option("Historical", "12"),
        new Option("Horror", "37"),
        new Option("Isekai", "49"),
        new Option("Josei", "2"),
        new Option("Lgbt+", "45"),
        new Option("Magic", "50"),
        new Option("Magical realism", "51"),
        new Option("Manhua", "52"),
        new Option("Martial Arts", "15"),
        new Option("Mature", "8"),
        new Option("Mecha", "34"),
        new Option("Military", "53"),
        new Option("Modern life", "54"),
        new Option("Movies", "55"),
        new Option("Mystery", "16"),
        new Option("Other", "64"),
        new Option("Psychological", "9"),
        new Option("Realistic fiction", "56"),
        new Option("Reincarnation", "43"),
        new Option("Romance", "1"),
        new Option("School Life", "21"),
        new Option("Sci-fi", "20"),
        new Option("Seinen", "10"),
        new Option("Shoujo", "38"),
        new Option("Shoujo ai", "57"),
        new Option("Shounen", "17"),
        new Option("Shounen Ai", "39"),
        new Option("Slice of Life", "13"),
        new Option("Smut", "29"),
        new Option("Sports", "42"),
        new Option("Supernatural", "18"),
        new Option("System", "58"),
        new Option("Tragedy", "32"),
        new Option("Urban", "63"),
        new Option("Urban life", "59"),
        new Option("Video games", "60"),
        new Option("War", "61"),
        new Option("Wuxia", "31"),
        new Option("Xianxia", "23"),
        new Option("Xuanhuan", "22"),
        new Option("Yaoi", "14"),
        new Option("Yuri", "62"));

    static final List<Option> GENRE_OPERATOR_OPTIONS = List.of(
        new Option("AND", "and"),
        new Option("OR", "or"),
        new Option("EXCLUDE", "exclude"));

    static final List<Option> CHAPTERS_OPTIONS = List.of(
        new Option("All", "0"),
        new Option("<50", "1,49"),
        new Option("50-100", "50,100"),
        new Option("100-200", "100,200"),
        new Option("200-500", "200,500"),
        new Option("500-1000", "500,1000"),
        new Option(">1000", "1001,1000000"));

    static final List<Option> RATING_OPERATOR_OPTIONS = List.of(
        new Option("min", "min"),
        new Option("max", "max"));

    static final List<Option> RATING_OPTIONS = List.of(
        new Option("none", "0"),
        new Option("1", "1"),
        new Option("2", "2"),
        new Option("3", "3"),
        new Option("4", "4"),
        new Option("5", "5"));

    static final List<Option> STATUS_OPTIONS = List.of(
        new Option("All", "-1"),
        new Option("Completed", "1"),
        new Option("Ongoing", "0"));

    static final List<Option> SORT_OPTIONS = List.of(
        new Option("Last Updated (Newest)", "date"),
        new Option("Rank (Top)", "rank-top"),
        new Option("Rating Score (Top)", "rating-score-top"),
        new Option("Review Count (Most)", "review"),
        new Option("Comment Count (Most)", "comment"),
        new Option("Bookmark Count (Most)", "bookmark"),
        new Option("Today Views (Most)", "today-view"),
        new Option("Monthly Views (Most)", "monthly-view"),
        new Option("Total Views (Most)", "total-view"),
        new Option("Chapter Count (Most)", "chapter-count-most"),
        new Option("Title (A>Z)", "abc"),
        new Option("Title (Z>A)", "cba"));

    static final List<Option> TAG_OPERATOR_OPTIONS = List.of(
        new Option("AND", "and"),
        new Option("OR", "or"));

    private static final Map<String, List<Option>> OPTIONS_BY_FILTER = Map.of(
        LANGUAGE, LANGUAGE_OPTIONS,
        GENRES, GENRE_OPTIONS,
        GENRE_OPERATOR, GENRE_OPERATOR_OPTIONS,
        CHAPTERS, CHAPTERS_OPTIONS,
        RATING_OPERATOR, RATING_OPERATOR_OPTIONS,
        RATING, RATING_OPTIONS,
        STATUS, STATUS_OPTIONS,
        SORT, SORT_OPTIONS,
        TAG_OPERATOR, TAG_OPERATOR_OPTIONS);

    static FilterList defaultFilterList(String sort) {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(checkBoxGroup(LANGUAGE, LANGUAGE_OPTIONS));
        filters.add(select(GENRE_OPERATOR, GENRE_OPERATOR_OPTIONS, indexOf(GENRE_OPERATOR_OPTIONS, "and")));
        filters.add(checkBoxGroup(GENRES, GENRE_OPTIONS));
        filters.add(select(CHAPTERS, CHAPTERS_OPTIONS, 0));
        filters.add(select(RATING_OPERATOR, RATING_OPERATOR_OPTIONS, 0));
        filters.add(select(RATING, RATING_OPTIONS, 0));
        filters.add(select(STATUS, STATUS_OPTIONS, 0));
        filters.add(select(SORT, SORT_OPTIONS, indexOf(SORT_OPTIONS, sort)));
        filters.add(select(TAG_OPERATOR, TAG_OPERATOR_OPTIONS, indexOf(TAG_OPERATOR_OPTIONS, "and")));
        filters.add(new Filter.TextFilter(AUTHOR));
        return new FilterList(filters);
    }

    static int indexOf(List<Option> options, String value) {
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

    static Filter.Group checkBoxGroup(String name, List<Option> options) {
        List<Filter<?>> items = new ArrayList<>(options.size());
        for (Option option : options) {
            items.add(new Filter.CheckBox(option.label()));
        }
        return new Filter.Group(name, items);
    }

    /** The form value behind an option label inside {@code group}, or {@code null} when unknown. */
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

    /** The form value a {@link Filter.Select} currently points at, or {@code null} when unknown. */
    static String selectedValue(Filter.Select select) {
        List<Option> options = OPTIONS_BY_FILTER.get(select.name());
        if (options == null) {
            return null;
        }
        Integer state = select.state();
        int index = state == null ? 0 : state;
        return index >= 0 && index < options.size() ? options.get(index).value() : null;
    }
}
