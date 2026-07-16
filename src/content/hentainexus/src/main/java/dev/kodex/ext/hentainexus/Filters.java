package dev.kodex.ext.hentainexus;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Filters {

    private Filters() {
    }

    static final String OFFSET = "Offset results by # pages";

    // Display label -> query key (mirrors AdvSearchEntryFilter.key = name.lowercase().removeSuffix("s")).
    private static final Map<String, String> ENTRY_FILTERS = ordered(
        "Tags", "tag", "Artists", "artist", "Authors", "author", "Circles", "circle",
        "Events", "event", "Parodies", "parody", "Magazines", "magazine", "Publishers", "publisher");

    static FilterList defaultFilterList() {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Header(
            "Separate items with commas (,). Prepend with dash (-) to exclude. "
                + "For items with multiple words, surround them with double quotes (\")."));
        for (String label : ENTRY_FILTERS.keySet()) {
            filters.add(new Filter.TextFilter(label));
        }
        filters.add(new Filter.Separator(""));
        filters.add(new Filter.TextFilter(OFFSET));
        return new FilterList(filters);
    }

    /** Builds the {@code [-]key:term} prefix prepended to the user's free-text query. */
    static String combineQuery(FilterList filters) {
        if (filters == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Filter<?> filter : filters.filters()) {
            if (filter instanceof Filter.TextFilter text) {
                String key = ENTRY_FILTERS.get(text.name());
                if (key == null || text.state().isBlank()) {
                    continue;
                }
                for (String token : splitFilterState(text.state())) {
                    boolean exclude = token.startsWith("-");
                    String term = exclude ? token.substring(1) : token;
                    if (exclude) {
                        out.append('-');
                    }
                    out.append(key).append(':').append(term).append(' ');
                }
            }
        }
        return out.toString();
    }

    /** Reads the optional page offset (0 if unset/invalid). */
    static int pageOffset(FilterList filters) {
        if (filters == null) {
            return 0;
        }
        for (Filter<?> filter : filters.filters()) {
            if (filter instanceof Filter.TextFilter text && OFFSET.equals(text.name())) {
                try {
                    return Integer.parseInt(text.state().trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** Comma-splits the field, keeping double-quoted phrases intact (mirrors the upstream splitter). */
    private static List<String> splitFilterState(String state) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < state.length(); i++) {
            char ch = state.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                current.append(ch);
            } else if (ch == ',' && !inQuotes) {
                addToken(tokens, current);
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        addToken(tokens, current);
        return tokens;
    }

    private static void addToken(List<String> tokens, StringBuilder current) {
        String token = current.toString().trim();
        if (!token.isEmpty()) {
            tokens.add(token);
        }
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
