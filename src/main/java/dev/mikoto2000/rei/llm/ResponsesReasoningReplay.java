package dev.mikoto2000.rei.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesMetadata;

/** Pure send-view validation. Storage and execution remain with the existing application. */
final class ResponsesReasoningReplay {
  private ResponsesReasoningReplay() {}

  static Prompt prepare(Prompt prompt, ResolvedLlmEndpoint endpoint) {
    if (endpoint.capabilities().reasoningReplay() != ReasoningReplayMode.ENCRYPTED) return prompt;
    for (var message : prompt.getInstructions()) {
      if (!(message instanceof AssistantMessage assistant)) continue;
      for (var part : assistant.getParts()) {
        if (!(part instanceof ReasoningPart reasoning)) continue;
        var payload = reasoning.payload();
        if (payload == null || payload.data().isBlank())
          throw new IllegalArgumentException("Responses reasoning payload is required");
        var itemId = reasoning.attributes().get(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE);
        if (itemId == null || itemId.isBlank())
          throw new IllegalArgumentException("Responses reasoning item id is required");
      }
    }
    return prompt;
  }
}
