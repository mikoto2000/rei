package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.annotation.Tool;

/** 複数ツールBatchingの結果と状態の契約を検証するテスト。 */
class MultiToolBatchingTest {
  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"readMultiFile", "grepMultiQuery", "writeMultiFile"})
  void descriptionsRequireBatchingAndShowMultipleTargets(String name) {
    var tool =
        java.util.Arrays.stream(Tools.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(Tool.class))
            .filter(annotation -> annotation != null && annotation.name().equals(name))
            .findFirst()
            .orElseThrow();
    assertThat(tool.description())
        .contains("independent", "one array", "same response", "documented limits", "Example:");
    assertThat(tool.description()).contains("},{");
  }
}
