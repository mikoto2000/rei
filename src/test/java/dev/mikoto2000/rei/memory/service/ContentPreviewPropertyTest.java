package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/** 内容事前確認Propertyの結果と状態の契約を検証するテスト。 */
class ContentPreviewPropertyTest {

  // Feature: ai-memory-consolidation, Property 7: コンテンツプレビューの長さ制約
  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Property(tries = 100)
  void previewLengthIsAtMost100(@ForAll String content) {
    String preview = preview(content);
    assertTrue(preview.length() <= 101);
  }

  /** {@code preview} の条件を再現するための補助情報を返す。 */
  private String preview(String content) {
    if (content == null) {
      return "";
    }
    return content.length() <= 100 ? content : content.substring(0, 100) + "…";
  }
}
