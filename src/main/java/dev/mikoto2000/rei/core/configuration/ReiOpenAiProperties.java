package dev.mikoto2000.rei.core.configuration;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.openai")
public class ReiOpenAiProperties {

  private List<String> baseUrls = new ArrayList<>();

  public List<String> getBaseUrls() {
    return baseUrls;
  }

  public void setBaseUrls(List<String> baseUrls) {
    this.baseUrls = baseUrls;
  }
}
