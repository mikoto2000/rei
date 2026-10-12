package dev.mikoto2000.rei.core.contextbudget;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties("rei.tool-result-delivery")
public class ToolResultDeliveryProperties {
  private int pageTargetTokens = 2048;
  private int pageMinTokens = 256;
  private int pageMaxTokens = 4096;
  // ContextAssembler already reserves 4000 model tokens; this is additional optional page headroom.
  private int pageSafetyTokens;
  public void validate() {
    if (pageMinTokens < 1 || pageTargetTokens < pageMinTokens || pageMaxTokens < pageTargetTokens
        || pageMaxTokens > 16000 || pageSafetyTokens < 0) throw new IllegalArgumentException("Invalid result delivery budgets");
  }
}
