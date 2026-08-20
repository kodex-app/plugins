package dev.kodex.plugin.readnovelfull;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.List;

/**
 * Browse filters for the ReadNovelFull-family sites. Both sources expose the same two choices — a novel
 * listing and a genre — but with their own option tables, so each source hands its own lists in.
 *
 * <p>Every option value is a site path segment ({@code sort/most-popular}, {@code genre/Action}); picking
 * a genre replaces the listing entirely, which is how the upstream plugin behaves.
 */
final class Filters {

    private Filters() {
    }

    /** A filter option: the label the UI shows and the path segment it browses. */
    record Option(String label, String value) {
    }

    static final String TYPE = "Novel Listing";
    static final String GENRE = "Genre (replaces the listing)";
    /** Placeholder for "no genre chosen", since a Select always has something selected. */
    static final String NO_GENRE = "None";

    // ---- Free Web Novel --------------------------------------------------------------------------

    static final List<Option> FWN_TYPE_OPTIONS = List.of(
        new Option("All", "sort/latest-release"),
        new Option("Chinese Novel", "sort/latest-release/chinese-novel"),
        new Option("Korean Novel", "sort/latest-release/korean-novel"),
        new Option("Japanese Novel", "sort/latest-release/japanese-novel"),
        new Option("English Novel", "sort/latest-release/english-novel"),
        new Option("Most Popular", "sort/most-popular"));

    static final List<Option> FWN_GENRE_OPTIONS = List.of(
        new Option("Action", "genre/Action"),
        new Option("Adult", "genre/Adult"),
        new Option("Adventure", "genre/Adventure"),
        new Option("Comedy", "genre/Comedy"),
        new Option("Drama", "genre/Drama"),
        new Option("Eastern", "genre/Eastern"),
        new Option("Ecchi", "genre/Ecchi"),
        new Option("Fantasy", "genre/Fantasy"),
        new Option("Game", "genre/Game"),
        new Option("Gender Bender", "genre/Gender+Bender"),
        new Option("Harem", "genre/Harem"),
        new Option("Historical", "genre/Historical"),
        new Option("Horror", "genre/Horror"),
        new Option("Josei", "genre/Josei"),
        new Option("Martial Arts", "genre/Martial+Arts"),
        new Option("Mature", "genre/Mature"),
        new Option("Mecha", "genre/Mecha"),
        new Option("Mystery", "genre/Mystery"),
        new Option("Psychological", "genre/Psychological"),
        new Option("Reincarnation", "genre/Reincarnation"),
        new Option("Romance", "genre/Romance"),
        new Option("School Life", "genre/School+Life"),
        new Option("Sci-fi", "genre/Sci-fi"),
        new Option("Seinen", "genre/Seinen"),
        new Option("Shoujo", "genre/Shoujo"),
        new Option("Shounen Ai", "genre/Shounen+Ai"),
        new Option("Shounen", "genre/Shounen"),
        new Option("Slice of Life", "genre/Slice+of+Life"),
        new Option("Smut", "genre/Smut"),
        new Option("Sports", "genre/Sports"),
        new Option("Supernatural", "genre/Supernatural"),
        new Option("Tragedy", "genre/Tragedy"),
        new Option("Wuxia", "genre/Wuxia"),
        new Option("Xianxia", "genre/Xianxia"),
        new Option("Xuanhuan", "genre/Xuanhuan"),
        new Option("Yaoi", "genre/Yaoi"));

    // ---- Novel Bin -------------------------------------------------------------------------------

    static final List<Option> NOVELBIN_TYPE_OPTIONS = List.of(
        new Option("Hot Novel", "sort/top-hot-novel"),
        new Option("Completed Novel", "sort/completed"),
        new Option("Most Popular", "sort/top-view-novel"));

    static final List<Option> NOVELBIN_GENRE_OPTIONS = List.of(
        new Option("Action", "genre/action"),
        new Option("Adventure", "genre/adventure"),
        new Option("Anime & comics", "genre/anime-&-comics"),
        new Option("Comedy", "genre/comedy"),
        new Option("Drama", "genre/drama"),
        new Option("Eastern", "genre/eastern"),
        new Option("Fan-fiction", "genre/fan-fiction"),
        new Option("Fanfiction", "genre/fanfiction"),
        new Option("Fantasy", "genre/fantasy"),
        new Option("Game", "genre/game"),
        new Option("Games", "genre/games"),
        new Option("Gender bender", "genre/gender-bender"),
        new Option("General", "genre/general"),
        new Option("Harem", "genre/harem"),
        new Option("Historical", "genre/historical"),
        new Option("Horror", "genre/horror"),
        new Option("Isekai", "genre/isekai"),
        new Option("Josei", "genre/josei"),
        new Option("Litrpg", "genre/litrpg"),
        new Option("Magic", "genre/magic"),
        new Option("Magical realism", "genre/magical-realism"),
        new Option("Martial arts", "genre/martial-arts"),
        new Option("Mature", "genre/mature"),
        new Option("Mecha", "genre/mecha"),
        new Option("Military", "genre/military"),
        new Option("Modern life", "genre/modern-life"),
        new Option("Myster", "genre/myster"),
        new Option("Mystery", "genre/mystery"),
        new Option("Other", "genre/other"),
        new Option("Other", "genre/other"),
        new Option("Psychological", "genre/psychological"),
        new Option("Reincarnation", "genre/reincarnation"),
        new Option("Romance", "genre/romance"),
        new Option("Romance.smut", "genre/romance.smut"),
        new Option("School life", "genre/school-life"),
        new Option("Sci-fi", "genre/sci-fi"),
        new Option("Seinen", "genre/seinen"),
        new Option("Shoujo", "genre/shoujo"),
        new Option("Shoujo ai", "genre/shoujo-ai"),
        new Option("Shounen", "genre/shounen"),
        new Option("Shounen ai", "genre/shounen-ai"),
        new Option("Slice of life", "genre/slice-of-life"),
        new Option("Smut", "genre/smut"),
        new Option("Sports", "genre/sports"),
        new Option("Supernatural", "genre/supernatural"),
        new Option("System", "genre/system"),
        new Option("Thriller", "genre/thriller"),
        new Option("Tragedy", "genre/tragedy"),
        new Option("Urban", "genre/urban"),
        new Option("Urban life", "genre/urban-life"),
        new Option("Video games", "genre/video-games"),
        new Option("War", "genre/war"),
        new Option("Wuxia", "genre/wuxia"),
        new Option("Xianxia", "genre/xianxia"),
        new Option("Xuanhuan", "genre/xuanhuan"),
        new Option("Yaoi", "genre/yaoi"),
        new Option("Yuri", "genre/yuri"));

    // ---- Construction ----------------------------------------------------------------------------

    static FilterList defaultFilterList(List<Option> types, List<Option> genres, String defaultType) {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(select(TYPE, types, indexOf(types, defaultType)));
        filters.add(select(GENRE, withNone(genres), 0));
        return new FilterList(filters);
    }

    /** Genre lists get a leading "None" entry so the picker can express "don't filter by genre". */
    static List<Option> withNone(List<Option> genres) {
        List<Option> all = new ArrayList<>(genres.size() + 1);
        all.add(new Option(NO_GENRE, ""));
        all.addAll(genres);
        return all;
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

    /** The path segment a {@link Filter.Select} points at, or {@code null} when it selects nothing. */
    static String selectedValue(Filter.Select select, List<Option> options) {
        Integer state = select.state();
        int index = state == null ? 0 : state;
        if (index < 0 || index >= options.size()) {
            return null;
        }
        String value = options.get(index).value();
        return value.isEmpty() ? null : value;
    }
}
