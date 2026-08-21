package dev.kodex.plugin.webnovel;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Browse filters for WebNovel's <em>novel</em> catalogue, mirroring the upstream LNReader plugin.
 *
 * <p>These do not map onto a single query string: the gender pick decides which of the two genre
 * lists applies, and a chosen genre becomes a <em>path segment</em> rather than a parameter — so the
 * values here are a mix of path fragments and query values, and {@code WebNovelNovelSource} decides
 * which is which.
 */
final class NovelFilters {

    private NovelFilters() {
    }

    /** A filter option: the label the UI shows and the path fragment or query value behind it. */
    record Option(String label, String value) {
    }

    static final String SORT = "Sort Results By";
    static final String STATUS = "Content Status";
    static final String GENDER = "Genres (Male/Female)";
    static final String MALE_GENRES = "Male Genres";
    static final String FEMALE_GENRES = "Female Genres";
    static final String TYPE = "Content Type";
    static final String FANFIC = "Search fanfics (Overrides other filters)";

    /** {@code orderBy} for the popular feed, and for the latest feed. */
    static final String SORT_POPULAR = "1";
    static final String SORT_LATEST = "5";
    /** The gender values, and the "no specific genre" sentinel each genre list uses. */
    static final String GENDER_MALE = "1";
    static final String GENDER_FEMALE = "2";
    /** "MTL" is not a sourceType — it is sourceType 1 plus a translateMode flag. */
    static final String TYPE_MTL = "3";

    static final List<Option> SORT_OPTIONS = List.of(
        new Option("Popular", "1"),
        new Option("Recommended", "2"),
        new Option("Most Collections", "3"),
        new Option("Rating", "4"),
        new Option("Time Updated", "5"));

    static final List<Option> STATUS_OPTIONS = List.of(
        new Option("All", "0"),
        new Option("Completed", "2"),
        new Option("Ongoing", "1"));

    static final List<Option> GENDER_OPTIONS = List.of(
        new Option("Male", "1"),
        new Option("Female", "2"));

    static final List<Option> MALE_GENRE_OPTIONS = List.of(
        new Option("All", "1"),
        new Option("Action", "novel-action-male"),
        new Option("Animation, Comics, Games", "novel-acg-male"),
        new Option("Eastern", "novel-eastern-male"),
        new Option("Fantasy", "novel-fantasy-male"),
        new Option("Games", "novel-games-male"),
        new Option("History", "novel-history-male"),
        new Option("Horror", "novel-horror-male"),
        new Option("Realistic", "novel-realistic-male"),
        new Option("Sci-fi", "novel-scifi-male"),
        new Option("Sports", "novel-sports-male"),
        new Option("Urban", "novel-urban-male"),
        new Option("War", "novel-war-male"));

    static final List<Option> FEMALE_GENRE_OPTIONS = List.of(
        new Option("All", "2"),
        new Option("Fantasy", "novel-fantasy-female"),
        new Option("General", "novel-general-female"),
        new Option("History", "novel-history-female"),
        new Option("LGBT+", "novel-lgbt-female"),
        new Option("Sci-fi", "novel-scifi-female"),
        new Option("Teen", "novel-teen-female"),
        new Option("Urban", "novel-urban-female"));

    static final List<Option> TYPE_OPTIONS = List.of(
        new Option("All", "0"),
        new Option("Translate", "1"),
        new Option("Original", "2"),
        new Option("MTL (Machine Translation)", "3"));

    private static final Map<String, List<Option>> OPTIONS_BY_FILTER = Map.of(
        SORT, SORT_OPTIONS,
        STATUS, STATUS_OPTIONS,
        GENDER, GENDER_OPTIONS,
        MALE_GENRES, MALE_GENRE_OPTIONS,
        FEMALE_GENRES, FEMALE_GENRE_OPTIONS,
        TYPE, TYPE_OPTIONS);

    static FilterList defaultFilterList(String sort) {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(select(SORT, SORT_OPTIONS, indexOf(SORT_OPTIONS, sort)));
        filters.add(select(STATUS, STATUS_OPTIONS, 0));
        filters.add(select(TYPE, TYPE_OPTIONS, 0));
        filters.add(select(GENDER, GENDER_OPTIONS, 0));
        filters.add(select(MALE_GENRES, MALE_GENRE_OPTIONS, 0));
        filters.add(select(FEMALE_GENRES, FEMALE_GENRE_OPTIONS, 0));
        filters.add(new Filter.TextFilter(FANFIC));
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

    /** The value a {@link Filter.Select} currently points at, or {@code null} when unknown. */
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
