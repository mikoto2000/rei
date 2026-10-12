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
  private long rawResultMaxBytes=16L*1024*1024;
  private long rawStoreMaxBytes=256L*1024*1024;
  private long rawCacheMaxBytes=8L*1024*1024;
  private ReviewLedger reviewLedger=new ReviewLedger();
  @Getter @Setter public static class ReviewLedger { private boolean enabled=true; private boolean requireCoverage=true; }
  public void validate() {
    if (pageMinTokens < 1 || pageTargetTokens < pageMinTokens || pageMaxTokens < pageTargetTokens
        || pageMaxTokens > 16000 || pageSafetyTokens < 0 || rawResultMaxBytes<1 || rawStoreMaxBytes<1 || rawCacheMaxBytes<0 || reviewLedger==null) throw new IllegalArgumentException("Invalid result delivery budgets");
  }
}
