package dev.mikoto2000.rei.core.contextbudget;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 語句による検索語句一覧の結果と状態の契約を検証するテスト。 */
class LexicalTermsTest {
  /** モデルへ渡す文脈の管理で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void sharedTermsPreserveExistingSqliteLexicalSemantics() {
    assertEquals(
        List.of("spring", "日本語", "25", "http", "get"),
        LexicalTerms.query("Spring SPRING 日本語 25 HTTP.get A"));
    assertEquals(List.of(), LexicalTerms.query(null));
    assertEquals(List.of(), LexicalTerms.query(" "));
  }
}
