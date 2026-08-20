package dev.kodex.plugin.readcomiconline;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReadComicOnline's search filters, mirroring the upstream extension. The UI edits {@link Filter} state
 * by <em>name</em>, so each fixed-option filter keeps a parallel table of the values the site expects.
 */
final class Filters {

    private Filters() {
    }

    /** A filter option: the label the UI shows and the value the site's query expects. */
    record Option(String label, String value) {
    }

    static final String GENRES = "Genres";
    static final String STATUS = "Status";
    static final String YEAR = "Publish Year";
    static final String SORT = "Sort By";
    static final String PUBLISHER = "Publisher";
    static final String WRITER = "Writer";
    static final String ARTIST = "Artist";

    /** The earliest year the site's Advanced Search offers. */
    private static final int EARLIEST_YEAR = 1920;

    static final List<Option> STATUS_OPTIONS = List.of(
        new Option("Any", ""),
        new Option("Completed", "Completed"),
        new Option("Ongoing", "Ongoing"));

    /** Sort values double as the path segment appended to a browse URL; "Alphabet" is the bare default. */
    static final List<Option> SORT_OPTIONS = List.of(
        new Option("Alphabet", ""),
        new Option("Popularity", "MostPopular"),
        new Option("Latest Update", "LatestUpdate"),
        new Option("New Comic", "Newest"));

    /**
     * Genre ids as used by {@code /AdvanceSearch}'s {@code ig}/{@code eg} parameters. Scraped from the
     * site's Advanced Search form, same as upstream.
     */
    static final List<Option> GENRE_OPTIONS = List.of(
        new Option("Action", "1"),
        new Option("Adventure", "2"),
        new Option("Anthology", "38"),
        new Option("Anthropomorphic", "46"),
        new Option("Biography", "41"),
        new Option("Children", "49"),
        new Option("Comedy", "3"),
        new Option("Crime", "17"),
        new Option("Drama", "19"),
        new Option("Family", "25"),
        new Option("Fantasy", "20"),
        new Option("Fighting", "31"),
        new Option("Graphic Novels", "5"),
        new Option("Historical", "28"),
        new Option("Horror", "15"),
        new Option("Leading Ladies", "35"),
        new Option("LGBTQ", "51"),
        new Option("Literature", "44"),
        new Option("Manga", "40"),
        new Option("Martial Arts", "4"),
        new Option("Mature", "8"),
        new Option("Military", "33"),
        new Option("Mini-Series", "56"),
        new Option("Movies & TV", "47"),
        new Option("Music", "55"),
        new Option("Mystery", "23"),
        new Option("Mythology", "21"),
        new Option("Personal", "48"),
        new Option("Political", "42"),
        new Option("Post-Apocalyptic", "43"),
        new Option("Psychological", "27"),
        new Option("Pulp", "39"),
        new Option("Religious", "53"),
        new Option("Robots", "9"),
        new Option("Romance", "32"),
        new Option("School Life", "52"),
        new Option("Sci-Fi", "16"),
        new Option("Slice of Life", "50"),
        new Option("Sport", "54"),
        new Option("Spy", "30"),
        new Option("Superhero", "22"),
        new Option("Supernatural", "24"),
        new Option("Suspense", "29"),
        new Option("Thriller", "18"),
        new Option("Vampires", "34"),
        new Option("Video Games", "37"),
        new Option("War", "26"),
        new Option("Western", "45"),
        new Option("Zombies", "36"));

    static List<Option> yearOptions() {
        List<Option> options = new ArrayList<>();
        options.add(new Option("Any", ""));
        for (int year = Year.now().getValue(); year >= EARLIEST_YEAR; year--) {
            options.add(new Option(Integer.toString(year), Integer.toString(year)));
        }
        return List.copyOf(options);
    }

    private static final Map<String, List<Option>> OPTIONS_BY_FILTER = Map.of(
        STATUS, STATUS_OPTIONS,
        SORT, SORT_OPTIONS);

    static FilterList defaultFilterList() {
        List<Filter<?>> filters = new ArrayList<>();
        List<Filter<?>> genres = new ArrayList<>(GENRE_OPTIONS.size());
        for (Option genre : GENRE_OPTIONS) {
            genres.add(new Filter.TriState(genre.label()));
        }
        filters.add(new Filter.Group(GENRES, genres));
        filters.add(select(STATUS, STATUS_OPTIONS));
        filters.add(select(YEAR, yearOptions()));
        filters.add(new Filter.Separator(""));
        filters.add(new Filter.Header(
            "The filters below are ignored when a search term, a publish year, or more than one genre is set "
                + "(a single included genre can still be sorted)."));
        filters.add(select(SORT, SORT_OPTIONS));
        filters.add(new Filter.TextFilter(PUBLISHER));
        filters.add(new Filter.TextFilter(WRITER));
        filters.add(new Filter.TextFilter(ARTIST));
        return new FilterList(filters);
    }

    static Filter.Select select(String name, List<Option> options) {
        List<String> labels = new ArrayList<>(options.size());
        for (Option option : options) {
            labels.add(option.label());
        }
        return new Filter.Select(name, labels, 0);
    }

    /** The genre id behind a label, or {@code null} when unknown. */
    static String genreId(String label) {
        for (Option option : GENRE_OPTIONS) {
            if (option.label().equals(label)) {
                return option.value();
            }
        }
        return null;
    }

    /**
     * The value a {@link Filter.Select} currently points at, or {@code null} when it sits on "Any".
     * The year select is resolved against a freshly built list because its options depend on the current year.
     */
    static String selectedValue(Filter.Select select) {
        List<Option> options = YEAR.equals(select.name()) ? yearOptions() : OPTIONS_BY_FILTER.get(select.name());
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
