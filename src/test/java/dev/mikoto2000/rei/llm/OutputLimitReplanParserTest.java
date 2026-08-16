package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OutputLimitReplanParserTest {

  private final OutputLimitReplanParser parser = new OutputLimitReplanParser();

  @Test
  void parsesPlannerJson() {
    OutputLimitReplanPlan plan = parser.parse("""
        {
          "subgoals": [
            {"id": "architecture", "goal": "アーキテクチャをレビューする"},
            {"id": "testing", "goal": "テスト戦略をレビューする"}
          ],
          "finalGoal": "結果を統合する"
        }
        """, 8);

    assertThat(plan.subgoals()).hasSize(2);
    assertThat(plan.subgoals().get(0).id()).isEqualTo("architecture");
    assertThat(plan.subgoals().get(0).goal()).isEqualTo("アーキテクチャをレビューする");
    assertThat(plan.finalGoal()).isEqualTo("結果を統合する");
  }

  @Test
  void extractsJsonFromCodeFence() {
    OutputLimitReplanPlan plan = parser.parse("""
        ```json
        {"subgoals":[{"id":"one","goal":"小さく実行する"}],"finalGoal":"統合する"}
        ```
        """, 8);

    assertThat(plan.subgoals()).hasSize(1);
    assertThat(plan.subgoals().get(0).goal()).isEqualTo("小さく実行する");
  }

  @Test
  void rejectsEmptySubgoals() {
    assertThatThrownBy(() -> parser.parse("{\"subgoals\":[],\"finalGoal\":\"統合\"}", 8))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("subgoals");
  }

  @Test
  void trimsSubgoalsToConfiguredLimit() {
    OutputLimitReplanPlan plan = parser.parse("""
        {
          "subgoals": [
            {"id": "one", "goal": "1"},
            {"id": "two", "goal": "2"},
            {"id": "three", "goal": "3"}
          ],
          "finalGoal": "統合"
        }
        """, 2);

    assertThat(plan.subgoals()).extracting(OutputLimitReplanSubgoal::id)
        .containsExactly("one", "two");
  }

  @Test
  void rejectsBlankGoal() {
    assertThatThrownBy(() -> parser.parse("{\"subgoals\":[{\"id\":\"x\",\"goal\":\" \"}],\"finalGoal\":\"統合\"}", 8))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("goal");
  }
}
