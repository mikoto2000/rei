package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.chatgpt.PlanDelegationProperties;
import dev.mikoto2000.rei.llm.StableToolDefinitions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** 補助エージェント実行環境モデルデータ形式の結果と状態の契約を検証するテスト。 */
class SubAgentRuntimeModelSchemaTest {
  /** 検証に使う保存や受け渡しに用いるデータ形式の変換。 */
  private final JsonMapper json = JsonMapper.builder().build();

  /** 生成済み定義一覧外部への公開現在選択肢および表記の統一について、意味を保持することを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"delegateTask", "delegateTasks"})
  void generatedDefinitionsExposeCurrentChoicesAndCanonicalizationPreservesTheirMeaning(
      String tool) {
    var registry = mock(SubAgentRegistry.class);
    when(registry.list()).thenReturn(List.of());
    var tools = new SubAgentTools(mock(SubAgentRunner.class), registry);
    var callback = tools.callback(tool);
    String original = callback.getToolDefinition().inputSchema();
    tools.setAutomatic(
        new PlanDelegationProperties(
            true, Set.of("default"), Set.of("gpt-6.1-sol", "gpt-6-astra")));
    var generated = json.readTree(callback.getToolDefinition().inputSchema());
    var rule = generated.findPath("then");
    assertThat(rule.path("properties").path("modelId").path("enum").toString())
        .isEqualTo("[\"gpt-6-astra\",\"gpt-6.1-sol\"]");
    assertThat(rule.path("required").toString()).isEqualTo("[\"modelId\"]");
    assertThat(
            json.readTree(
                StableToolDefinitions.callbacks(List.of(callback))
                    .getFirst()
                    .getToolDefinition()
                    .inputSchema()))
        .isEqualTo(generated);
    tools.setAutomatic(
        new PlanDelegationProperties(true, Set.of("default"), Set.of("updated-model")));
    assertThat(callback.getToolDefinition().inputSchema())
        .contains("updated-model")
        .doesNotContain("gpt-6.1-sol", "gpt-6-astra");
    tools.setAutomatic(
        new PlanDelegationProperties(false, Set.of("default"), Set.of("updated-model")));
    assertThat(callback.getToolDefinition().inputSchema()).isEqualTo(original);
  }
}
