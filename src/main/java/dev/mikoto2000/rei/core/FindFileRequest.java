package dev.mikoto2000.rei.core;

import java.util.List;

/**
 * 共通の実行基盤で受け渡す検索ファイル要求を表す。
 *
 * @param keywords 検索語句一覧
 * @param maxResults 検索結果の件数上限
 */
public record FindFileRequest(List<String> keywords, Integer maxResults) {

  /** 検索結果の件数上限。 */
  public static final int MAX_RESULTS = 500;

  /** 受け取った情報からFindFileRequestを作成する。 */
  public FindFileRequest {
    if (keywords == null
        || keywords.stream().noneMatch(value -> value != null && !value.isBlank())) {
      throw new IllegalArgumentException("keywords must contain at least one non-blank value");
    }
    if (maxResults != null && (maxResults < 1 || maxResults > MAX_RESULTS)) {
      throw new IllegalArgumentException("maxResults must be between 1 and " + MAX_RESULTS);
    }
  }
}
