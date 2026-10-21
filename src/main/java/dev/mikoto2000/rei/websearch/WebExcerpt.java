package dev.mikoto2000.rei.websearch;

import java.util.List;

/**
 * Web上の情報の調査で受け渡すWeb根拠として示す抜粋を表す。
 *
 * <p>Source HTML offsets and ranges in returned content; no duplicate copy of the excerpt text.
 *
 * @param kind 種類
 * @param headings 見出し一覧
 * @param position 位置
 * @param sourceStart 取得元開始
 * @param sourceEnd 取得元終了
 * @param anchor 基準位置
 * @param contentStart 内容開始
 * @param contentEnd 内容終了
 * @param truncated 省略の有無
 */
public record WebExcerpt(
    String kind,
    List<String> headings,
    int position,
    int sourceStart,
    int sourceEnd,
    String anchor,
    int contentStart,
    int contentEnd,
    boolean truncated) {
  /** 受け取った情報からWebExcerptを作成する。 */
  public WebExcerpt {
    headings = List.copyOf(headings);
  }
}
