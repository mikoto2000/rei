package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class OutputLimitDetectorTest {

  @Test
  void detectsLengthFinishReasonAsOutputLimitReached() {
    ChatResponse response = responseWithFinishReason("length");

    assertThat(OutputLimitDetector.isOutputLimitReached(response)).isTrue();
  }

  @Test
  void treatsStopFinishReasonAsCompleted() {
    ChatResponse response = responseWithFinishReason("stop");

    assertThat(OutputLimitDetector.isOutputLimitReached(response)).isFalse();
  }

  @Test
  void ignoresMissingFinishReason() {
    ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));

    assertThat(OutputLimitDetector.isOutputLimitReached(response)).isFalse();
  }

  private static ChatResponse responseWithFinishReason(String finishReason) {
    ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
        .finishReason(finishReason)
        .build();
    return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"), metadata)));
  }
}
