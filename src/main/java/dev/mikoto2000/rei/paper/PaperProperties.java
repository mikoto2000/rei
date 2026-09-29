package dev.mikoto2000.rei.paper;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rei.paper")
public class PaperProperties {
  private PaperSummary.Mode defaultSummaryMode = PaperSummary.Mode.STANDARD;
  private PaperTranslation.Mode defaultTranslationMode = PaperTranslation.Mode.TECHNICAL;
  private boolean enabled = true;
  private int maxPdfBytes = 20_000_000,
      maxResponseBytes = 4_000_000,
      maxPages = 200,
      maxExtractedChars = 1_000_000,
      maxSections = 500,
      maxSectionChars = 16000;
  private int translationChunkSize = 4000,
      summaryInputLimit = 16000,
      maxLibrarySearchResults = 50,
      defaultLimit = 10,
      maxLimit = 100,
      retries = 2;
  private Duration timeout = Duration.ofSeconds(30),
      llmTimeout = Duration.ofMinutes(3),
      backoff = Duration.ofMillis(500);
  private String userAgent = "Rei-PaperResearch/1.0",
      openAlexApiKey = "",
      promptVersion = "1",
      glossaryVersion = "1";

  public void validate() {
    if (maxPdfBytes < 1
        || maxResponseBytes < 1
        || maxPages < 1
        || maxExtractedChars < 1
        || maxSections < 1
        || maxSectionChars < 1
        || translationChunkSize < 1
        || summaryInputLimit < 1000
        || maxLibrarySearchResults < 1
        || maxLibrarySearchResults > 100
        || maxLimit < 1
        || maxLimit > 100
        || defaultLimit < 1
        || defaultLimit > maxLimit
        || retries < 0
        || retries > 5
        || timeout.isNegative()
        || timeout.isZero()
        || llmTimeout.isNegative()
        || llmTimeout.isZero()
        || backoff.isNegative())
      throw new IllegalArgumentException("Invalid rei.paper resource limits");
  }
}
