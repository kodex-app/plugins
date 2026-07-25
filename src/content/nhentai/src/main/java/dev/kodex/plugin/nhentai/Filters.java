package dev.kodex.plugin.nhentai;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.List;

/**
 * The search filters. nhentai has no structured search parameters beyond {@code sort} — every
 * refinement is a token in the single {@code query} string, in the syntax the v2 API documents:
 * {@code artist:name}, {@code language:english}, {@code tag:"big breasts"}, {@code -negated}. So
 * these filters exist only to build that string.
 */
final class Filters {

    private Filters() {
    }

    /** {@code query} is mandatory and must be non-empty; this is nhentai's match-anything token. */
    static final String MATCH_ALL = "*";

    static final String SORT = "Sort by";
    static final String TAG = "Tag";
    static final String ARTIST = "Artist";
    static final String CHARACTER = "Character";
    static final String PARODY = "Parody / Series";
    static final String GROUP = "Group / Circle";

    private static final List<String> SORT_LABELS =
        List.of("Recent", "Popular Today", "Popular Week", "Popular Month", "All Time Popular");
    // The sort values the v2 /search endpoint accepts — anything else is a validation error.
    private static final List<String> SORT_VALUES =
        List.of("date", "popular-today", "popular-week", "popular-month", "popular");

    static FilterList defaultFilterList() {
        // No language filter: language is chosen by picking the source (English/Japanese/Chinese).
        return FilterList.of(
            new Filter.Header("Filters are combined with text search"),
            new Filter.Separator(""),
            new Filter.Select(SORT, SORT_LABELS),
            new Filter.Separator(""),
            new Filter.Header("Tag filters (exact name, e.g. \"sole male\"); prefix with - to exclude"),
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
     * (AND). {@link #MATCH_ALL} when nothing was entered.
     */
    static String combineQuery(String query, FilterList filters) {
        List<String> parts = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            parts.add(query.trim());
        }
        addTerm(parts, filters, TAG, "tag");
        addTerm(parts, filters, ARTIST, "artist");
        addTerm(parts, filters, CHARACTER, "character");
        addTerm(parts, filters, PARODY, "parody");
        addTerm(parts, filters, GROUP, "group");
        return parts.isEmpty() ? MATCH_ALL : String.join(" ", parts);
    }

    /** Appends {@code [-]<prefix>:<term>}, keeping a leading {@code -} (exclude) outside the prefix. */
    private static void addTerm(List<String> parts, FilterList filters, String name, String prefix) {
        String state = text(filters, name);
        if (state == null || state.isBlank()) {
            return;
        }
        String term = state.trim();
        String negation = "";
        if (term.startsWith("-")) {
            negation = "-";
            term = term.substring(1).trim();
        }
        if (!term.isEmpty()) {
            parts.add(negation + prefix + ":" + quoteIfNeeded(term));
        }
    }

    /** Multi-word values only match as a tag when quoted — {@code tag:"big breasts"}. */
    private static String quoteIfNeeded(String term) {
        boolean quoted = term.length() > 1 && term.startsWith("\"") && term.endsWith("\"");
        return quoted || term.indexOf(' ') < 0 ? term : "\"" + term + "\"";
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
