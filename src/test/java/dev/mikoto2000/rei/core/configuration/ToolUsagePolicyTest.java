package dev.mikoto2000.rei.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ツール使用状況判断基準の結果と状態の契約を検証するテスト。 */
class ToolUsagePolicyTest {
  /** 機能の組み立てで、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void compositionPreservesOwnPromptAndDoesNotDuplicatePolicy() {
    String prompt = ToolUsagePolicy.appendTo("Own instructions");
    assertThat(prompt).startsWith("Own instructions\n\n").contains(ToolUsagePolicy.INSTRUCTIONS);
    assertThat(ToolUsagePolicy.appendTo(prompt)).isEqualTo(prompt);
    assertThat(ToolUsagePolicy.appendTo(null)).isEqualTo(ToolUsagePolicy.INSTRUCTIONS);
  }
}
