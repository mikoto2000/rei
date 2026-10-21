package dev.mikoto2000.rei.paper;

import java.util.List;

/**
 * 論文の調査と整理で受け渡す論文検索検索条件を表す。
 *
 * @param query 検索条件
 * @param fromYear 起点年
 * @param toYear 終点年
 * @param authors 著者一覧
 * @param venues 発表先一覧
 * @param openAccessOnly 開始アクセス限定
 * @param sort 並び順
 * @param limit 上限
 */
public record PaperSearchQuery(
    String query,
    Integer fromYear,
    Integer toYear,
    List<String> authors,
    List<String> venues,
    boolean openAccessOnly,
    Sort sort,
    int limit) {
  /** 論文の調査と整理における並び順の選択肢。 */
  public enum Sort {
    /** 関連度。 */
    RELEVANCE,
    /** 新しい情報を優先する並び順。 */
    NEWEST,
    /** CITATION件数。 */
    CITATION_COUNT
  }

  /** 受け取った情報からPaperSearchQueryを作成する。 */
  public PaperSearchQuery {
    if (query == null
        || query.isBlank()
        || query.length() > 500
        || limit < 1
        || limit > 100
        || fromYear != null && (fromYear < 1000 || fromYear > 9999)
        || toYear != null && (toYear < 1000 || toYear > 9999)
        || fromYear != null && toYear != null && fromYear > toYear)
      throw new PaperException(PaperException.Code.INVALID_QUERY, "検索語・年・件数を確認してください (1〜100件)");
    authors = authors == null ? List.of() : List.copyOf(authors);
    venues = venues == null ? List.of() : List.copyOf(venues);
    sort = sort == null ? Sort.RELEVANCE : sort;
  }
}
