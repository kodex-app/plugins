package dev.kodex.plugin.comick;

import dev.kodex.spi.content.filter.Filter;

import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comick's search filters, mirroring the upstream extension's {@code Filters.kt}. The UI edits
 * {@link Filter} state by <em>name</em>, so each fixed-option filter keeps a parallel table of the
 * query values {@code /api/search} expects.
 *
 * <p>Genres and tags are <b>not</b> listed here: Comick serves them from {@code /api/metadata}, and
 * {@link ComickSource} builds those two groups from the live response so the options never go stale.
 */
final class Filters {

    private Filters() {
    }

    /** A fixed filter option: the label the UI shows and the value {@code /api/search} expects. */
    record Option(String label, String value) {
    }

    static final String SORT = "Sort";
    static final String DEMOGRAPHIC = "Demographic";
    static final String TYPE = "Type";
    static final String GENRE = "Genre";
    static final String TAGS = "Tags";
    static final String CREATED_AT = "Created At";
    static final String MIN_CHAPTERS = "Minimum Chapters";
    static final String STATUS = "Status";
    static final String CONTENT_RATING = "Content Rating";
    static final String RELEASE_FROM = "Release From";
    static final String RELEASE_TO = "Release To";

    static final List<String> SORT_LABELS =
        List.of("Latest", "Popular", "Highest Rating", "Last Uploaded");
    /** {@code order_by} values, positionally aligned with {@link #SORT_LABELS}. */
    static final List<String> SORT_VALUES =
        List.of("created_at", "user_follow_count", "rating", "uploaded");

    static final List<Option> DEMOGRAPHIC_OPTIONS = List.of(
        new Option("Shounen", "1"),
        new Option("Josei", "2"),
        new Option("Seinen", "3"),
        new Option("Shoujo", "4"),
        new Option("None", "0"));

    static final List<Option> TYPE_OPTIONS = List.of(
        new Option("Manga", "jp"),
        new Option("Manhwa", "kr"),
        new Option("Manhua", "cn"),
        new Option("Others", "others"));

    static final List<Option> CREATED_AT_OPTIONS = List.of(
        new Option("Any", ""),
        new Option("3 days ago", "3"),
        new Option("7 days ago", "7"),
        new Option("30 days ago", "30"),
        new Option("3 months ago", "90"),
        new Option("6 months ago", "180"),
        new Option("1 year ago", "365"),
        new Option("2 years ago", "730"));

    static final List<Option> STATUS_OPTIONS = List.of(
        new Option("Any", ""),
        new Option("Ongoing", "1"),
        new Option("Completed", "2"),
        new Option("Cancelled", "3"),
        new Option("Hiatus", "4"));

    static final List<Option> CONTENT_RATING_OPTIONS = List.of(
        new Option("Any", ""),
        new Option("Safe", "safe"),
        new Option("Suggestive", "suggestive"),
        new Option("Erotica", "erotica"));

    /** "Any", then every year from the current one back to 1990, then a catch-all bucket. */
    static List<Option> releaseYearOptions() {
        List<Option> options = new ArrayList<>();
        options.add(new Option("Any", ""));
        for (int year = Year.now().getValue(); year >= 1990; year--) {
            options.add(new Option(Integer.toString(year), Integer.toString(year)));
        }
        options.add(new Option("Before 1990", "0"));
        return List.copyOf(options);
    }

    private static final Map<String, List<Option>> OPTIONS_BY_FILTER = Map.of(
        DEMOGRAPHIC, DEMOGRAPHIC_OPTIONS,
        TYPE, TYPE_OPTIONS,
        CREATED_AT, CREATED_AT_OPTIONS,
        STATUS, STATUS_OPTIONS,
        CONTENT_RATING, CONTENT_RATING_OPTIONS);

    static List<String> labelsOf(List<Option> options) {
        List<String> labels = new ArrayList<>(options.size());
        for (Option option : options) {
            labels.add(option.label());
        }
        return labels;
    }

    /** The query value behind a checkbox label inside {@code group}, or {@code null} when unknown. */
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

    /**
     * The query value a {@link Filter.Select} currently points at, or {@code null} when it sits on the
     * "Any" entry. Release-year selects are resolved against a freshly built list because their options
     * depend on the current year.
     */
    static String selectedValue(Filter.Select select) {
        List<Option> options = RELEASE_FROM.equals(select.name()) || RELEASE_TO.equals(select.name())
            ? releaseYearOptions()
            : OPTIONS_BY_FILTER.get(select.name());
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

    static Filter.Group checkBoxGroup(String name, List<Option> options) {
        List<Filter<?>> boxes = new ArrayList<>(options.size());
        for (Option option : options) {
            boxes.add(new Filter.CheckBox(option.label()));
        }
        return new Filter.Group(name, boxes);
    }

    static Filter.Select select(String name, List<Option> options) {
        return new Filter.Select(name, labelsOf(options), 0);
    }

    /** A tri-state group whose option labels are Comick genre/tag names, paired with their slugs. */
    static Filter.Group triStateGroup(String name, Map<String, String> slugsByName) {
        List<Filter<?>> options = new ArrayList<>(slugsByName.size());
        for (String label : slugsByName.keySet()) {
            options.add(new Filter.TriState(label));
        }
        return new Filter.Group(name, options);
    }

    static Map<String, String> newOrderedMap() {
        return new LinkedHashMap<>();
    }
}
