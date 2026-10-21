package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 出力上限計画の見直し入力の解釈の結果と状態の契約を検証するテスト。 */
class OutputLimitReplanParserTest {

  /** 検証に使う入力の解釈。 */
  private final OutputLimitReplanParser parser = new OutputLimitReplanParser();

  /** 用途に応じたモデル利用で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void parsesPlannerJson() {
    OutputLimitReplanPlan plan =
        parser.parse(
            """
        {
          "subgoals": [
            {"id": "architecture", "goal": "アーキテクチャをレビューする"},
            {"id": "testing", "goal": "テスト戦略をレビューする"}
          ],
          "finalGoal": "結果を統合する"
        }
        """,
            8);

    assertThat(plan.subgoals()).hasSize(2);
    assertThat(plan.subgoals().get(0).id()).isEqualTo("architecture");
    assertThat(plan.subgoals().get(0).goal()).isEqualTo("アーキテクチャをレビューする");
    assertThat(plan.finalGoal()).isEqualTo("結果を統合する");
  }

  /** 用途に応じたモデル利用で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void extractsJsonFromCodeFence() {
    OutputLimitReplanPlan plan =
        parser.parse(
            """
        ```json
        {"subgoals":[{"id":"one","goal":"小さく実行する"}],"finalGoal":"統合する"}
        ```
        """,
            8);

    assertThat(plan.subgoals()).hasSize(1);
    assertThat(plan.subgoals().get(0).goal()).isEqualTo("小さく実行する");
  }

  /** 空の小さな目標を受け付けないことを確認する。 */
  @Test
  void rejectsEmptySubgoals() {
    assertThatThrownBy(() -> parser.parse("{\"subgoals\":[],\"finalGoal\":\"統合\"}", 8))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("subgoals");
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void trimsSubgoalsToConfiguredLimit() {
    OutputLimitReplanPlan plan =
        parser.parse(
            """
        {
          "subgoals": [
            {"id": "one", "goal": "1"},
            {"id": "two", "goal": "2"},
            {"id": "three", "goal": "3"}
          ],
          "finalGoal": "統合"
        }
        """,
            2);

    assertThat(plan.subgoals())
        .extracting(OutputLimitReplanSubgoal::id)
        .containsExactly("one", "two");
  }

  /** 空白の目標を受け付けないことを確認する。 */
  @Test
  void rejectsBlankGoal() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    "{\"subgoals\":[{\"id\":\"x\",\"goal\":\" \"}],\"finalGoal\":\"統合\"}", 8))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("goal");
  }
}
