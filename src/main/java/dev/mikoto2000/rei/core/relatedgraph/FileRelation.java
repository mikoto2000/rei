package dev.mikoto2000.rei.core.relatedgraph;

import java.time.OffsetDateTime;

/**
 * 1 件のファイル間関係。
 *
 * @param sourcePath      関係の起点ファイル
 * @param targetPath      関係の対象ファイル
 * @param type            関係種別（REFERENCES / IMPORTS / TESTS / RELATED など）
 * @param evidence        関係がどこから判明したか（SEARCH / IMPORT / EXPLICIT_TOOL_RESULT など）
 * @param lastConfirmedAt 最後に確認された日時
 */
public record FileRelation(
    String sourcePath,
    String targetPath,
    String type,
    String evidence,
    OffsetDateTime lastConfirmedAt) {

  /** 同一関係を識別するキー。 */
  public String key() {
    return sourcePath + "\u0001" + targetPath + "\u0001" + type;
  }
}
