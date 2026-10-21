package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 出力上限検出の窓口の結果と状態の契約を検証するテスト。 */
class OutputLimitDetectorTest {

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void detectsLengthFinishReasonAsOutputLimitReached() {
    ChatResponse response = responseWithFinishReason("length");

    assertThat(OutputLimitDetector.isOutputLimitReached(response)).isTrue();
  }

  /** 完了理由形式完了済みのを停止することを確認する。 */
  @Test
  void treatsStopFinishReasonAsCompleted() {
    ChatResponse response = responseWithFinishReason("stop");

    assertThat(OutputLimitDetector.isOutputLimitReached(response)).isFalse();
  }

  /** 用途に応じたモデル利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void ignoresMissingFinishReason() {
    ChatResponse response =
        new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));

    assertThat(OutputLimitDetector.isOutputLimitReached(response)).isFalse();
  }

  /** {@code responseWithFinishReason} の条件を再現するための補助情報を返す。 */
  private static ChatResponse responseWithFinishReason(String finishReason) {
    ChatGenerationMetadata metadata =
        ChatGenerationMetadata.builder().finishReason(finishReason).build();
    return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"), metadata)));
  }
}
