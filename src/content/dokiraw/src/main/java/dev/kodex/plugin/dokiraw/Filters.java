package dev.kodex.plugin.dokiraw;

import java.util.List;

final class Filters {

    private Filters() {
    }

    static final String GENRE = "Genre";

    static final List<String> GENRES = List.of(
        "All",
        "フルカラー",
        "Ecchi",
        "エロい",
        "コメディ",
        "ロマンス",
        "アクション",
        "スポーツ",
        "ファンタジー",
        "SF",
        "異世界",
        "心理的",
        "青年");
}
