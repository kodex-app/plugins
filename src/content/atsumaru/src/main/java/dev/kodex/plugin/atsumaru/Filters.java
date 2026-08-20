package dev.kodex.plugin.atsumaru;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Atsumaru's search filters and the id tables behind them, mirroring the upstream extension's
 * {@code Filters.kt}. The UI edits {@link Filter} state by <em>name</em>, so each table keeps the
 * name → Typesense id mapping the search query actually needs.
 */
final class Filters {

    private Filters() {
    }

    /** A filter option: the label the UI shows and the id Atsumaru's Typesense index expects. */
    record Entry(String name, String id) {
    }

    static final String GENRES = "Genres";
    static final String TAGS = "Tags";
    static final String TYPE = "Manga Type";
    static final String STATUS = "Publishing Status";
    static final String YEAR = "Year (e.g., 2024)";
    static final String MIN_CHAPTERS = "Minimum Chapters";
    static final String SORT = "Sort By";
    static final String ADULT = "Show Adult Content";
    static final String OFFICIAL = "Only Official Translations";

    static final List<String> SORT_LABELS =
        List.of("Popularity", "Trending", "Date Added", "Release Date", "Top Rated", "Title");
    /** Typesense sort keys, positionally aligned with {@link #SORT_LABELS}. */
    static final List<String> SORT_VALUES =
        List.of("views", "trending", "dateAdded", "released", "mbRating", "title");

    static final List<Entry> GENRE_LIST = List.of(
        new Entry("Action", "39"),
        new Entry("Adult", "46"),
        new Entry("Adventure", "37"),
        new Entry("Boys Love", "180"),
        new Entry("Comedy", "6"),
        new Entry("Drama", "31"),
        new Entry("Fantasy", "36"),
        new Entry("Girls Love", "4"),
        new Entry("Hentai", "10"),
        new Entry("Historical", "45"),
        new Entry("Horror", "44"),
        new Entry("Martial Arts", "29"),
        new Entry("Mystery", "32"),
        new Entry("Psychological", "18"),
        new Entry("Romance", "9"),
        new Entry("Sci-Fi", "1"),
        new Entry("Slice of Life", "7"),
        new Entry("Smut", "41"),
        new Entry("Supernatural", "22"),
        new Entry("Thriller", "19"),
        new Entry("Tragedy", "5"));

    static final List<Entry> TAG_LIST = List.of(
        new Entry("Blackmail", "285"),
        new Entry("Cooking", "669"),
        new Entry("Crimes", "288"),
        new Entry("Crossdressing", "167"),
        new Entry("Murder", "250"),
        new Entry("Prostitution", "366"),
        new Entry("Swordplay", "337"),
        new Entry("Working", "248"),
        new Entry("Josei", "43"),
        new Entry("Seinen", "8"),
        new Entry("Shoujo", "40"),
        new Entry("Shounen", "38"),
        new Entry("Otaku", "264"),
        new Entry("Tsundere", "313"),
        new Entry("Yandere", "315"),
        new Entry("Animal Characteristics", "274"),
        new Entry("Beautiful Female Lead", "72"),
        new Entry("Big Breasts", "123"),
        new Entry("Flat Chest", "320"),
        new Entry("Glasses-Wearing Male Lead", "71"),
        new Entry("Handsome Male Lead", "68"),
        new Entry("Kemonomimi", "279"),
        new Entry("MILF", "339"),
        new Entry("Small Breasts", "124"),
        new Entry("Young Male Lead", "787"),
        new Entry("Adult Cast", "159"),
        new Entry("Bisexual", "382"),
        new Entry("Ensemble Cast", "362"),
        new Entry("Female Lead", "59"),
        new Entry("Male Lead", "58"),
        new Entry("Non-Human Protagonist", "247"),
        new Entry("Primarily Adult Cast", "158"),
        new Entry("Primarily Female Cast", "333"),
        new Entry("Primarily Male Cast", "335"),
        new Entry("Primarily Teen Cast", "334"),
        new Entry("Strong Female Lead", "69"),
        new Entry("Strong Male Lead", "67"),
        new Entry("Adapted to Anime", "166"),
        new Entry("Based on a Light Novel", "76"),
        new Entry("Based on a Novel", "75"),
        new Entry("Based on a Video Game", "77"),
        new Entry("Based on a Web Novel", "74"),
        new Entry("College", "257"),
        new Entry("Company", "1205"),
        new Entry("Countryside", "415"),
        new Entry("Europe", "405"),
        new Entry("Foreign", "336"),
        new Entry("High School", "162"),
        new Entry("Hospital", "760"),
        new Entry("Japan", "225"),
        new Entry("School", "107"),
        new Entry("School Clubs", "356"),
        new Entry("Amnesia", "283"),
        new Entry("Appearance Different from Personality", "651"),
        new Entry("Caught in the Act", "874"),
        new Entry("Dead Family Member", "831"),
        new Entry("Family Drama", "848"),
        new Entry("Flashbacks", "449"),
        new Entry("Gender Bender", "12"),
        new Entry("Love Triangle", "125"),
        new Entry("Male Lead Falls in Love First", "653"),
        new Entry("Misunderstandings", "647"),
        new Entry("Past Plays a Big Role", "648"),
        new Entry("Reincarnation", "126"),
        new Entry("Secret Identity", "260"),
        new Entry("Time Manipulation", "311"),
        new Entry("Time Skip", "172"),
        new Entry("Time Travel", "249"),
        new Entry("Tragic Past", "898"),
        new Entry("Weak to Strong", "1064"),
        new Entry("Delinquents", "239"),
        new Entry("Detectives", "240"),
        new Entry("Idols", "281"),
        new Entry("Maids", "116"),
        new Entry("Office Lady", "312"),
        new Entry("Office Worker", "429"),
        new Entry("School Girl", "788"),
        new Entry("Teachers", "175"),
        new Entry("Age Gap", "106"),
        new Entry("Childhood Friends", "97"),
        new Entry("Coworkers", "286"),
        new Entry("Female Harem", "163"),
        new Entry("Friends to Lovers", "243"),
        new Entry("Friendship", "242"),
        new Entry("Harem", "20"),
        new Entry("Heterosexual", "108"),
        new Entry("Incest", "174"),
        new Entry("Infidelity", "231"),
        new Entry("Interspecies Relationship", "308"),
        new Entry("Love-Hate Relationship", "889"),
        new Entry("Master-Servant Relationship", "406"),
        new Entry("Older Female Younger Male", "114"),
        new Entry("Older Male Younger Female", "649"),
        new Entry("Older Uke Younger Seme", "880"),
        new Entry("Siblings", "254"),
        new Entry("Student-Student Relationship", "573"),
        new Entry("Student-Teacher Relationship", "177"),
        new Entry("Twins", "253"),
        new Entry("Chinese Ambience", "588"),
        new Entry("European Ambience", "450"),
        new Entry("Fantasy World", "642"),
        new Entry("Feudal Japan", "606"),
        new Entry("Game Elements", "399"),
        new Entry("Game World", "641"),
        new Entry("Isekai", "94"),
        new Entry("Isekaied Into a Novel", "258"),
        new Entry("Mecha", "11"),
        new Entry("Mythology", "259"),
        new Entry("Urban", "338"),
        new Entry("Urban Fantasy", "261"),
        new Entry("Anal Intercourse", "100"),
        new Entry("Bondage", "280"),
        new Entry("Boobjob", "381"),
        new Entry("Borderline H", "448"),
        new Entry("Cunnilingus", "171"),
        new Entry("Defloration", "306"),
        new Entry("Dubious Consent", "985"),
        new Entry("Ecchi", "21"),
        new Entry("Erotica", "14"),
        new Entry("Exhibitionism", "287"),
        new Entry("Group Intercourse", "373"),
        new Entry("Handjob", "303"),
        new Entry("Lolicon", "28"),
        new Entry("Masturbation", "161"),
        new Entry("Mature", "15"),
        new Entry("Nakadashi", "169"),
        new Entry("Netorare", "232"),
        new Entry("Nudity", "109"),
        new Entry("Oral Intercourse", "99"),
        new Entry("Outdoor Intercourse", "307"),
        new Entry("Public Intercourse", "103"),
        new Entry("Rape", "95"),
        new Entry("Sex Addict", "650"),
        new Entry("Sex Toys", "289"),
        new Entry("Shotacon", "35"),
        new Entry("Teens Love", "374"),
        new Entry("Threesome", "173"),
        new Entry("Virginity", "369"),
        new Entry("Animals", "278"),
        new Entry("Cats", "284"),
        new Entry("Demons", "160"),
        new Entry("Ghosts", "229"),
        new Entry("Gods", "176"),
        new Entry("Monsters", "395"),
        new Entry("Non-human", "547"),
        new Entry("Vampires", "252"),
        new Entry("21st century", "132"),
        new Entry("Betrayal", "403"),
        new Entry("Bullying", "235"),
        new Entry("Cohabitation", "228"),
        new Entry("Coming of Age", "117"),
        new Entry("Danmei", "305"),
        new Entry("Depression", "1090"),
        new Entry("Family Life", "282"),
        new Entry("Female Empowerment", "1816"),
        new Entry("Forbidden Love", "699"),
        new Entry("Gore", "262"),
        new Entry("Gourmet", "2"),
        new Entry("Harlequin", "304"),
        new Entry("Jealousy", "881"),
        new Entry("LGBTQ+", "326"),
        new Entry("Love Confession", "882"),
        new Entry("Marriage", "360"),
        new Entry("Mature Romance", "241"),
        new Entry("Medical", "350"),
        new Entry("Military", "230"),
        new Entry("Music", "27"),
        new Entry("Nobility", "127"),
        new Entry("Obsessive Love", "893"),
        new Entry("Orphans", "237"),
        new Entry("Religion", "498"),
        new Entry("Reunion", "984"),
        new Entry("Revenge", "227"),
        new Entry("Royalty", "128"),
        new Entry("School Life", "42"),
        new Entry("Shoujo Ai", "47"),
        new Entry("Shounen Ai", "23"),
        new Entry("Special Ability", "883"),
        new Entry("Sports", "30"),
        new Entry("Suicide", "309"),
        new Entry("Super Powers", "236"),
        new Entry("Unrequited Love", "226"),
        new Entry("Violence", "830"),
        new Entry("War", "238"),
        new Entry("Yaoi", "16"),
        new Entry("Yuri", "33"),
        new Entry("4-Koma", "105"),
        new Entry("Anthology", "113"),
        new Entry("Collection of Stories", "111"),
        new Entry("Doujinshi", "24"),
        new Entry("Episodic", "115"),
        new Entry("Full Color", "57"),
        new Entry("Korean Novels", "1111"),
        new Entry("Light Novel", "466"),
        new Entry("Longstrip", "93"),
        new Entry("One Shot", "110"),
        new Entry("Web Comic", "428"),
        new Entry("Web Novel", "427"),
        new Entry("Magic", "121"));

    static final List<Entry> TYPE_LIST = List.of(
        new Entry("Manga", "Manga"),
        new Entry("Manhwa", "Manwha"),
        new Entry("Manhua", "Manhua"),
        new Entry("OEL", "OEL"));

    static final List<Entry> STATUS_LIST = List.of(
        new Entry("Ongoing", "Ongoing"),
        new Entry("Completed", "Completed"),
        new Entry("Hiatus", "Hiatus"),
        new Entry("Canceled", "Canceled"));

    private static final Map<String, Map<String, String>> IDS_BY_GROUP = Map.of(
        GENRES, index(GENRE_LIST),
        TAGS, index(TAG_LIST),
        TYPE, index(TYPE_LIST),
        STATUS, index(STATUS_LIST));

    private static Map<String, String> index(List<Entry> entries) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Entry e : entries) {
            map.put(e.name(), e.id());
        }
        return Map.copyOf(map);
    }

    /** The default filter list handed to the UI. {@code adultOn} pre-checks "Show Adult Content". */
    static FilterList defaultFilterList(boolean adultOn) {
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Separator(""));
        filters.add(triStateGroup(GENRES, GENRE_LIST));
        filters.add(triStateGroup(TAGS, TAG_LIST));
        filters.add(checkBoxGroup(TYPE, TYPE_LIST));
        filters.add(checkBoxGroup(STATUS, STATUS_LIST));
        filters.add(new Filter.TextFilter(YEAR));
        filters.add(new Filter.TextFilter(MIN_CHAPTERS));
        filters.add(new Filter.Sort(SORT, SORT_LABELS, new Filter.Sort.Selection(0, false)));
        filters.add(new Filter.CheckBox(ADULT, adultOn));
        filters.add(new Filter.CheckBox(OFFICIAL, false));
        return new FilterList(filters);
    }

    private static Filter.Group triStateGroup(String name, List<Entry> entries) {
        List<Filter<?>> options = new ArrayList<>();
        for (Entry e : entries) {
            options.add(new Filter.TriState(e.name()));
        }
        return new Filter.Group(name, options);
    }

    private static Filter.Group checkBoxGroup(String name, List<Entry> entries) {
        List<Filter<?>> options = new ArrayList<>();
        for (Entry e : entries) {
            options.add(new Filter.CheckBox(e.name()));
        }
        return new Filter.Group(name, options);
    }

    /** The Typesense id for an option label inside {@code group}, or {@code null} when unknown. */
    static String idOf(String group, String optionName) {
        Map<String, String> ids = IDS_BY_GROUP.get(group);
        return ids == null ? null : ids.get(optionName);
    }
}
