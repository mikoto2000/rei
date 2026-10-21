package dev.mikoto2000.rei.feed;

import java.util.List;

/**
 * フィードの購読と収集で受け渡すフィードOPML取り込み結果を表す。
 *
 * @param imported 取り込んだ内容
 * @param skipped 対象外
 * @param failed 失敗
 */
public record FeedOpmlImportResult(List<Feed> imported, List<Entry> skipped, List<Entry> failed) {
  /** 受け取った情報からFeedOpmlImportResultを作成する。 */
  public FeedOpmlImportResult {
    imported = List.copyOf(imported);
    skipped = List.copyOf(skipped);
    failed = List.copyOf(failed);
  }

  /**
   * フィードの購読と収集で受け渡す項目を表す。
   *
   * @param subscription 購読
   * @param reason 理由
   */
  public record Entry(OpmlSubscription subscription, String reason) {}
}
