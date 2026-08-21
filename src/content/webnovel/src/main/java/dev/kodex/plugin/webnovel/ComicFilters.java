package dev.kodex.plugin.webnovel;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WebNovel's comic browse filters, mirroring the upstream Keiyoushi extension. The UI edits
 * {@link Filter} state by <em>name</em>, so each filter keeps a parallel table of the values the
 * site's category endpoint expects.
 */
final class ComicFilters {

    private ComicFilters() {
    }

    /** A filter option: the label the UI shows and the value the category endpoint expects. */
    record Option(String label, String value) {
    }

    static final String SORT_BY = "Sort By";
    static final String CONTENT_STATUS = "Content status";
    static final String GENRE = "Genre";

    /** {@code orderBy} for the popular feed, and for the latest feed. */
    static final String SORT_POPULAR = "1";
    static final String SORT_LATEST = "5";

    static final List<Option> SORT_OPTIONS = List.of(
        new Option("Popular", "1"),
        new Option("Recommended", "2"),
        new Option("Most collections", "3"),
        new Option("Rating", "4"),
        new Option("Time updated", "5"));

    static final List<Option> STATUS_OPTIONS = List.of(
        new Option("All", "0"),
        new Option("Ongoing", "1"),
        new Option("Completed", "2"));

    static final List<Option> GENRE_OPTIONS = List.of(
        new Option("All", "0"),
        new Option("Action", "60002"),
        new Option("Adventure", "60014"),
        new Option("Comedy", "60011"),
        new Option("Cooking", "60009"),
        new Option("Diabolical", "60027"),
        new Option("Drama", "60024"),
        new Option("Eastern", "60006"),
        new Option("Fantasy", "60022"),
        new Option("Harem", "60017"),
        new Option("History", "60018"),
        new Option("Horror", "60015"),
        new Option("Inspiring", "60013"),
        new Option("LGBT+", "60029"),
        new Option("Magic", "60016"),
        new Option("Mystery", "60008"),
        new Option("Romance", "60003"),
        new Option("School", "60007"),
        new Option("Sci-fi", "60004"),
        new Option("Slice of Life", "60019"),
        new Option("Sports", "60023"),
        new Option("Transmigration", "60012"),
        new Option("Urban", "60005"),
        new Option("Wuxia", "60010"));

    private static final Map<String, List<Option>> OPTIONS_BY_FILTER = Map.of(
        SORT_BY, SORT_OPTIONS,
        CONTENT_STATUS, STATUS_OPTIONS,
        GENRE, GENRE_OPTIONS);

    static FilterList defaultFilterList(String sort) {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Header("Ignored when a search term is given."));
        filters.add(new Filter.Separator(""));
        filters.add(select(CONTENT_STATUS, STATUS_OPTIONS, 0));
        filters.add(select(SORT_BY, SORT_OPTIONS, indexOf(SORT_OPTIONS, sort)));
        filters.add(select(GENRE, GENRE_OPTIONS, 0));
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
