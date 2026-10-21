package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 言語モデル引数補正設定の結果と状態の契約を検証するテスト。 */
class LlmArgumentCorrectionPropertiesTest {
  /** 検証に使う照合キー。 */
  private static final String KEY = "rei.llm.output-limit.max-tool-argument-corrections-per-run";

  /** {@code bind} の条件を再現するための補助情報を返す。 */
  private LlmProperties bind(String value) {
    return new Binder(new MapConfigurationPropertySource(Map.of(KEY, value)))
        .bind("rei.llm", Bindable.of(LlmProperties.class))
        .get();
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @ParameterizedTest
  @ValueSource(ints = {0, 3, 10})
  void configuredLimitBindsFromItsDocumentedKeyIncludingZeroToDisable(int value) {
    assertThat(bind(Integer.toString(value)).getOutputLimit().getMaxToolArgumentCorrectionsPerRun())
        .isEqualTo(value);
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void defaultAndNullUseFiveWithoutTreatingZeroAsMissing() {
    var properties = new LlmProperties();
    assertThat(properties.getOutputLimit().getMaxToolArgumentCorrectionsPerRun()).isEqualTo(5);
    properties.getOutputLimit().setMaxToolArgumentCorrectionsPerRun(0);
    assertThat(properties.getOutputLimit().getMaxToolArgumentCorrectionsPerRun()).isZero();
    properties.getOutputLimit().setMaxToolArgumentCorrectionsPerRun(null);
    assertThat(properties.getOutputLimit().getMaxToolArgumentCorrectionsPerRun()).isEqualTo(5);
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void negativeCorrectionLimitsFailConfigurationBinding() {
    assertThatThrownBy(() -> bind("-1")).isInstanceOf(BindException.class);
    assertThatThrownBy(
            () -> new LlmProperties().getOutputLimit().setMaxToolArgumentCorrectionsPerRun(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
