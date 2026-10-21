package dev.mikoto2000.rei.llm;

import java.util.Locale;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 用途に応じたモデル利用に必要な出力上限検出の窓口を扱う。 */
public final class OutputLimitDetector {

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private OutputLimitDetector() {}

  /** 用途に応じたモデル利用において、出力上限Reachedかどうかを判定する。 */
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
