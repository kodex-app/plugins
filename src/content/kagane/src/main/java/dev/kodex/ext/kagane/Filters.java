package dev.kodex.ext.kagane;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Filters {

    private Filters() {
    }

    static final String SORT = "Sort By";
    static final String CONTENT_RATING = "Content Rating";
    static final String FORMAT = "Format";
    static final String STATUS = "Status";

    static final List<String> SORT_LABELS = List.of(
        "Relevance", "Popular (Total Views)", "Popular (Average Views)", "Popular (Today)",
        "Popular (Week)", "Popular (Month)", "Latest", "By Name", "Books count", "Created at");
    static final List<String> SORT_VALUES = List.of(
        "", "total_views", "avg_views", "avg_views_today", "avg_views_week", "avg_views_month",
        "updated_at", "series_name", "books_count", "created_at");

    private static final List<String> FORMAT_OPTIONS = List.of("Manga", "Manhwa", "Manhua", "Comic", "Other");

    // label -> API value
    private static final Map<String, String> STATUS_OPTIONS = ordered(
        "Ongoing", "Ongoing", "Completed", "Completed", "Hiatus", "Hiatus", "Cancelled", "Abandoned");

    static FilterList defaultFilterList(List<String> contentRatings) {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Sort(SORT, SORT_LABELS, new Filter.Sort.Selection(0, false)));

        List<Filter<?>> ratings = new ArrayList<>();
        for (String rating : contentRatings) {
            ratings.add(new Filter.CheckBox(rating, true)); // labels are the API values; all enabled by default
        }
        filters.add(new Filter.Group(CONTENT_RATING, ratings));

        List<Filter<?>> formats = new ArrayList<>();
        for (String format : FORMAT_OPTIONS) {
            formats.add(new Filter.CheckBox(format));
        }
        filters.add(new Filter.Group(FORMAT, formats));

        List<Filter<?>> statuses = new ArrayList<>();
        for (String status : STATUS_OPTIONS.keySet()) {
            statuses.add(new Filter.CheckBox(status));
        }
        filters.add(new Filter.Group(STATUS, statuses));

        return new FilterList(filters);
    }

    /** Maps a checkbox label back to the API value for its group. */
    static String optionValue(String groupName, String label) {
        return switch (groupName) {
            case STATUS -> STATUS_OPTIONS.getOrDefault(label, label);
            // CONTENT_RATING and FORMAT use the label verbatim (already the API value).
            default -> label;
        };
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
