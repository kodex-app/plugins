package dev.kodex.plugin.nhentai;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.List;

/**
 * The search filters, mirroring the Mihon extension's {@code getFilterList()}. nhentai has no
 * structured search parameters beyond {@code sort} — every refinement is a token in the single
 * {@code query} string ({@code artist:foo}, {@code language:english}, a bare tag name), so these
 * filters only build that string.
 */
final class Filters {

    private Filters() {
    }

    static final String SORT = "Sort by";
    static final String LANGUAGE = "Language";
    static final String TAG = "Tag";
    static final String ARTIST = "Artist";
    static final String CHARACTER = "Character";
    static final String PARODY = "Parody / Series";
    static final String GROUP = "Group / Circle";

    private static final List<String> SORT_LABELS =
        List.of("Recent", "Popular Today", "Popular Week", "All Time Popular");
    private static final List<String> SORT_VALUES =
        List.of("date", "popular-today", "popular-week", "popular");

    private static final List<String> LANGUAGE_LABELS = List.of("All", "English", "Japanese", "Chinese");
    private static final List<String> LANGUAGE_VALUES = List.of("", "english", "japanese", "chinese");

    static FilterList defaultFilterList() {
        return FilterList.of(
            new Filter.Header("Filters are combined with text search"),
            new Filter.Separator(""),
            new Filter.Select(SORT, SORT_LABELS),
            new Filter.Select(LANGUAGE, LANGUAGE_LABELS),
            new Filter.Separator(""),
            new Filter.Header("Tag filters (exact name, e.g. \"sole male\")"),
            new Filter.TextFilter(TAG),
            new Filter.TextFilter(ARTIST),
            new Filter.TextFilter(CHARACTER),
            new Filter.TextFilter(PARODY),
            new Filter.TextFilter(GROUP));
    }

    /** The {@code sort} query parameter, or {@code null} when the list carries no sort filter. */
    static String sort(FilterList filters) {
        return selected(filters, SORT, SORT_VALUES);
    }

    /**
     * The free-text query plus every non-blank filter term, space-joined the way nhentai reads them
     * (AND). {@code "*"} when nothing was entered — nhentai's match-anything query.
     */
    static String combineQuery(String query, FilterList filters) {
        List<String> parts = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            parts.add(query.trim());
        }
        addTerm(parts, filters, TAG, null);
        addTerm(parts, filters, ARTIST, "artist");
        addTerm(parts, filters, CHARACTER, "character");
        addTerm(parts, filters, PARODY, "parody");
        addTerm(parts, filters, GROUP, "group");
        String language = selected(filters, LANGUAGE, LANGUAGE_VALUES);
        if (language != null && !language.isBlank()) {
            parts.add("language:" + language);
        }
        return parts.isEmpty() ? "*" : String.join(" ", parts);
    }

    private static void addTerm(List<String> parts, FilterList filters, String name, String prefix) {
        String state = text(filters, name);
        if (state == null || state.isBlank()) {
            return;
        }
        parts.add(prefix == null ? state.trim() : prefix + ":" + state.trim());
    }

    private static String text(FilterList filters, String name) {
        if (filters == null) {
            return null;
        }
        for (Filter<?> filter : filters.filters()) {
            if (filter instanceof Filter.TextFilter t && name.equals(t.name())) {
                return t.state();
            }
        }
        return null;
    }

    /** The value at the {@link Filter.Select}'s selected index, or {@code null} if absent/out of range. */
    private static String selected(FilterList filters, String name, List<String> values) {
        if (filters == null) {
            return null;
        }
        for (Filter<?> filter : filters.filters()) {
            if (filter instanceof Filter.Select select && name.equals(select.name())) {
                int state = select.state() == null ? 0 : select.state();
                return state >= 0 && state < values.size() ? values.get(state) : null;
            }
        }
        return null;
    }
}
