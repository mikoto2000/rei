package dev.mikoto2000.rei.voice;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties(prefix="rei.voice.correction")
public class VoiceCorrectionProperties {
  private boolean enabled;
  private int timeoutMs=5000;
  private int maxInputChars=500;
  private int maxContextChars=1000;
  private int maxOutputTokens=512;
  private int maxConcurrentRequests=1;
  private int maxQueuedRequests=3;
  private int maxDictionaryEntries=64;
  private long maxTotalTokens=4096;
  public void validate() {
    if(timeoutMs<1 || timeoutMs>60000 || maxInputChars<1 || maxInputChars>2000 || maxContextChars<0 || maxContextChars>4000
        || maxOutputTokens<1 || maxOutputTokens>4096 || maxConcurrentRequests<1 || maxConcurrentRequests>4
        || maxQueuedRequests<1 || maxQueuedRequests>16 || maxDictionaryEntries<0 || maxDictionaryEntries>128 || maxTotalTokens<0)
      throw new IllegalArgumentException("Invalid voice correction limits");
  }
}
