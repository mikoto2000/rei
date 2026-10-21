package dev.mikoto2000.rei.core.stagnation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Stagnation Detector の Bean 定義。 */
@Configuration(proxyBeanMethods = false)
public class StagnationConfiguration {

  /** 実行の停滞への対処で用いる停滞検出の窓口を返す。 */
  @Bean
  public StagnationDetector stagnationDetector() {
    return new StagnationDetector();
  }
}
