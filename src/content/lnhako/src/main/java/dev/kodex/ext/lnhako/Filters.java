package dev.kodex.ext.lnhako;

import dev.kodex.spi.content.filter.Filter;
import dev.kodex.spi.content.filter.FilterList;
import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.List;

/**
 * Hako advanced-search ({@code /tim-kiem-nang-cao}) filters. Params: {@code title}, {@code author},
 * {@code illustrator} (text); {@code status}, {@code seriestype}, {@code sapxep} (single values); and
 * {@code selectgenres}/{@code rejectgenres} — comma-separated genre <em>ids</em> to include/exclude
 * (modelled as a tri-state group). Genre id↔name is scraped from the form's {@code data-genre-id} toggles.
 */
final class Filters {

    private Filters() {
    }

    static final String SORT = "Sắp xếp";
    static final String STATUS = "Tình trạng";
    static final String SERIES_TYPE = "Phân loại";
    static final String AUTHOR = "Tác giả";
    static final String ILLUSTRATOR = "Họa sĩ";
    static final String GENRES = "Thể loại";

    static final List<String> SORT_LABELS = List.of(
        "Tên A-Z", "Tên Z-A", "Mới cập nhật", "Mới đăng", "Lượt xem", "Top tháng", "Theo dõi", "Số từ");
    static final List<String> SORT_VALUES = List.of(
        "", "tentruyenza", "capnhat", "truyenmoi", "top", "topthang", "theodoi", "sotu");

    static final List<String> STATUS_LABELS = List.of("Tất cả", "Đang tiến hành", "Tạm ngưng", "Hoàn thành");
    static final List<String> STATUS_VALUES = List.of("0", "1", "2", "3");

    static final List<String> TYPE_LABELS = List.of("Tất cả", "Truyện dịch", "AI dịch", "Sáng tác");
    static final List<String> TYPE_VALUES = List.of("0", "1", "2", "3");

    // Genre labels and their Hako ids (from the /tim-kiem-nang-cao data-genre-id toggles), parallel lists.
    static final List<String> GENRE_LABELS = List.of(
        "Action", "Adapted to Anime", "Adapted to Drama CD", "Adapted to Manga", "Adapted to Manhua",
        "Adapted to Manhwa", "Adventure", "Age Gap", "Boys Love", "Character Growth", "Chinese Novel",
        "Comedy", "Cooking", "Different Social Status", "Drama", "Ecchi", "English Novel", "Fanfiction",
        "Fantasy", "Female Protagonist", "Game", "Gender Bender", "Harem", "Historical", "Horror", "Isekai",
        "Josei", "Korean Novel", "Magic", "Martial Arts", "Mecha", "Military", "Misunderstanding", "Mystery",
        "Netorare", "Obsession", "One shot", "Otome Game", "Parody", "Psychological", "Reverse Harem",
        "Romance", "Satire", "School Life", "Science Fiction", "Seinen", "Shoujo", "Shoujo ai", "Shounen",
        "Shounen ai", "Slice of Life", "Slow Life", "Sports", "Super Power", "Supernatural", "Suspense",
        "Tragedy", "Wars", "Web Novel", "Workplace", "Wuxia", "Xianxia", "Yandere", "Yuri");
    static final List<String> GENRE_IDS = List.of(
        "1", "49", "51", "50", "64",
        "65", "2", "52", "60", "54", "39",
        "3", "43", "56", "4", "5", "40", "62",
        "6", "59", "45", "7", "8", "35", "9", "30",
        "33", "34", "44", "37", "11", "36", "58", "12",
        "32", "69", "38", "46", "61", "23", "47",
        "22", "66", "13", "14", "31", "15", "16", "26",
        "17", "18", "55", "19", "24", "20", "25",
        "21", "53", "29", "57", "67", "68", "63", "48");

    /** {@code defaultSort} pre-selects the initial sort (e.g. {@code "top"} for popular, {@code "capnhat"} for latest, {@code ""} for the user filter list). */
    static FilterList defaultFilterList(String defaultSort) {
        int sortIndex = Math.max(0, SORT_VALUES.indexOf(defaultSort));
        List<Filter<?>> filters = new ArrayList<>();
        filters.add(new Filter.Select(SORT, SORT_LABELS, sortIndex));
        filters.add(new Filter.Select(STATUS, STATUS_LABELS, 0));
        filters.add(new Filter.Select(SERIES_TYPE, TYPE_LABELS, 0));
        filters.add(new Filter.TextFilter(AUTHOR));
        filters.add(new Filter.TextFilter(ILLUSTRATOR));
        List<Filter<?>> genres = new ArrayList<>();
        for (String label : GENRE_LABELS) {
            genres.add(new Filter.TriState(label));
        }
        filters.add(new Filter.Group(GENRES, genres));
        return new FilterList(filters);
    }

    /** Applies the filter state to a {@code /tim-kiem-nang-cao} query (title is added by the caller from the search term). */
    static void applyToUrl(HttpUrl.Builder builder, FilterList filters) {
        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
        for (Filter<?> filter : filters.filters()) {
            switch (filter) {
                case Filter.Select s when SORT.equals(s.name()) -> {
                    String v = valueAt(SORT_VALUES, s.state());
                    if (v != null && !v.isEmpty()) {
                        builder.addQueryParameter("sapxep", v);
                    }
                }
                case Filter.Select s when STATUS.equals(s.name()) -> {
                    String v = valueAt(STATUS_VALUES, s.state());
                    if (v != null && !"0".equals(v)) {
                        builder.addQueryParameter("status", v);
                    }
                }
                case Filter.Select s when SERIES_TYPE.equals(s.name()) -> {
                    String v = valueAt(TYPE_VALUES, s.state());
                    if (v != null && !"0".equals(v)) {
                        builder.addQueryParameter("seriestype", v);
                    }
                }
                case Filter.TextFilter t when AUTHOR.equals(t.name()) -> {
                    if (t.state() != null && !t.state().isBlank()) {
                        builder.addQueryParameter("author", t.state().trim());
                    }
                }
                case Filter.TextFilter t when ILLUSTRATOR.equals(t.name()) -> {
                    if (t.state() != null && !t.state().isBlank()) {
                        builder.addQueryParameter("illustrator", t.state().trim());
                    }
                }
                case Filter.Group g when GENRES.equals(g.name()) -> {
                    for (Filter<?> gf : g.state()) {
                        if (gf instanceof Filter.TriState ts) {
                            int idx = GENRE_LABELS.indexOf(ts.name());
                            if (idx < 0) {
                                continue;
                            }
                            if (ts.isIncluded()) {
                                include.add(GENRE_IDS.get(idx));
                            } else if (ts.isExcluded()) {
                                exclude.add(GENRE_IDS.get(idx));
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (!include.isEmpty()) {
            builder.addQueryParameter("selectgenres", String.join(",", include));
        }
        if (!exclude.isEmpty()) {
            builder.addQueryParameter("rejectgenres", String.join(",", exclude));
        }
    }

    private static String valueAt(List<String> values, Integer state) {
        if (state == null || values.isEmpty()) {
            return null;
        }
        return values.get(Math.floorMod(state, values.size()));
    }
}
