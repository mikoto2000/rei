package dev.mikoto2000.rei.core.datasource;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Spring の設定解決にも OS 標準のデータディレクトリを提供する。 */
public final class ReiDataDirectoryEnvironment implements EnvironmentPostProcessor, Ordered {
  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER - 1;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    environment.getPropertySources().addLast(
        new MapPropertySource("reiDataDirectory", Map.of(
            "rei.data-dir", ReiDataDirectory.current().toString())));
  }
}
