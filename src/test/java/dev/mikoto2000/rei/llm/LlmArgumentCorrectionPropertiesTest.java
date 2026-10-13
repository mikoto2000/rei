package dev.mikoto2000.rei.llm;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.*;

class LlmArgumentCorrectionPropertiesTest {
  private static final String KEY="rei.llm.output-limit.max-tool-argument-corrections-per-run";
  private LlmProperties bind(String value) {
    return new Binder(new MapConfigurationPropertySource(Map.of(KEY,value)))
        .bind("rei.llm",Bindable.of(LlmProperties.class)).get();
  }
  @ParameterizedTest @ValueSource(ints={0,3,10})
  void configuredLimitBindsFromItsDocumentedKeyIncludingZeroToDisable(int value) {
    assertThat(bind(Integer.toString(value)).getOutputLimit().getMaxToolArgumentCorrectionsPerRun()).isEqualTo(value);
  }
  @Test void defaultAndNullUseFiveWithoutTreatingZeroAsMissing() {
    var properties=new LlmProperties();
    assertThat(properties.getOutputLimit().getMaxToolArgumentCorrectionsPerRun()).isEqualTo(5);
    properties.getOutputLimit().setMaxToolArgumentCorrectionsPerRun(0);
    assertThat(properties.getOutputLimit().getMaxToolArgumentCorrectionsPerRun()).isZero();
    properties.getOutputLimit().setMaxToolArgumentCorrectionsPerRun(null);
    assertThat(properties.getOutputLimit().getMaxToolArgumentCorrectionsPerRun()).isEqualTo(5);
  }
  @Test void negativeCorrectionLimitsFailConfigurationBinding() {
    assertThatThrownBy(()->bind("-1")).isInstanceOf(BindException.class);
    assertThatThrownBy(()->new LlmProperties().getOutputLimit().setMaxToolArgumentCorrectionsPerRun(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
