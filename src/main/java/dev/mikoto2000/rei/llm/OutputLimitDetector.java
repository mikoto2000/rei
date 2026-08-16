package dev.mikoto2000.rei.llm;

import java.util.Locale;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

public final class OutputLimitDetector {

  private OutputLimitDetector() {
  }

  public static boolean isOutputLimitReached(ChatResponse response) {
    if (response == null) {
      return false;
    }
    Generation generation = response.getResult();
    if (generation == null || generation.getMetadata() == null) {
      return false;
    }
    String finishReason = generation.getMetadata().getFinishReason();
    return finishReason != null && "length".equals(finishReason.strip().toLowerCase(Locale.ROOT));
  }
}
