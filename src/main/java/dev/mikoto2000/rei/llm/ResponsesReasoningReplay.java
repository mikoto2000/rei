package dev.mikoto2000.rei.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesMetadata;

/** Pure send-view validation. Storage and execution remain with the existing application. */
final class ResponsesReasoningReplay {
  static final String OWNER_CONTEXT_KEY = "rei.llm.replay-owner";
  static final String ORIGIN_OWNER = "rei.llm.replay-origin-owner";
  static final String ORIGIN_ENDPOINT = "rei.llm.replay-origin-endpoint";

  private ResponsesReasoningReplay() {}

  static Prompt prepare(Prompt prompt, ResolvedLlmEndpoint endpoint) {
    String requestOwner =
        endpoint.capabilities().reasoningReplay() == ReasoningReplayMode.ENCRYPTED
            ? owner(prompt)
            : null;
    for (var message : prompt.getInstructions()) {
      if (!(message instanceof AssistantMessage assistant)) continue;
      for (var part : assistant.getParts()) {
        if (!(part instanceof org.springframework.ai.chat.messages.part.TextPart
                || part instanceof ReasoningPart
                || part instanceof org.springframework.ai.chat.messages.part.ToolCallPart)
            || org.springframework.ai.chat.messages.part.StreamingParts.isPartial(part))
          throw new IllegalArgumentException("Responses replay part is not supported or complete");
        var status = part.attributes().get(OpenAiResponsesMetadata.ITEM_STATUS_ATTRIBUTE);
        if (status != null && !"completed".equals(status))
          throw new IllegalArgumentException("Responses replay part is not complete");
        if (!(part instanceof ReasoningPart reasoning)) continue;
        if (endpoint.capabilities().reasoningReplay() != ReasoningReplayMode.ENCRYPTED)
          throw new IllegalArgumentException("Responses reasoning replay mode is incompatible");
        var payload = reasoning.payload();
        if (payload == null || payload.data().isBlank())
          throw new IllegalArgumentException("Responses reasoning payload is required");
        if (!OpenAiResponsesMetadata.PROVIDER.equals(payload.provider())
            || !OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND.equals(payload.kind()))
          throw new IllegalArgumentException("Responses reasoning payload is incompatible");
        var itemId = reasoning.attributes().get(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE);
        if (itemId == null || itemId.isBlank())
          throw new IllegalArgumentException("Responses reasoning item id is required");
        var metadata = assistant.getMetadata();
        if (!(metadata.get(ORIGIN_OWNER) instanceof String originOwner)
            || !(metadata.get(ORIGIN_ENDPOINT) instanceof String originEndpoint))
          throw new IllegalArgumentException("Responses reasoning replay origin is required");
        if (!originOwner.equals(requestOwner))
          throw new IllegalArgumentException("Responses reasoning replay owner mismatch");
        if (!originEndpoint.equals(endpoint.fingerprint()))
          throw new IllegalArgumentException("Responses reasoning replay endpoint mismatch");
      }
    }
    return prompt;
  }

  static org.springframework.ai.chat.model.ChatResponse bind(
      org.springframework.ai.chat.model.ChatResponse response,
      Prompt prompt,
      ResolvedLlmEndpoint endpoint) {
    if (endpoint.capabilities().reasoningReplay() != ReasoningReplayMode.ENCRYPTED) return response;
    String requestOwner = owner(prompt);
    var generations =
        response.getResults().stream()
            .map(generation -> {
              var output = generation.getOutput();
              var metadata = new java.util.HashMap<String, Object>(output.getMetadata());
              metadata.put(ORIGIN_OWNER, requestOwner);
              metadata.put(ORIGIN_ENDPOINT, endpoint.fingerprint());
              return new org.springframework.ai.chat.model.Generation(
                  output.mutate().properties(metadata).build(), generation.getMetadata());
            })
            .toList();
    return new org.springframework.ai.chat.model.ChatResponse(generations, response.getMetadata());
  }

  static void validateComplete(
      org.springframework.ai.chat.model.ChatResponse response,
      Prompt request,
      ResolvedLlmEndpoint endpoint) {
    try {
      var messages =
          response.getResults().stream()
              .<org.springframework.ai.chat.messages.Message>map(
                  generation -> generation.getOutput())
              .toList();
      prepare(new Prompt(messages, request.getOptions()), endpoint);
    } catch (IllegalArgumentException invalid) {
      throw new ResponsesTransportException(
          ResponsesTransportException.Category.INVALID_RESPONSE,
          ResponsesTransportException.usage(response.getMetadata().getUsage()));
    }
  }

  private static String owner(Prompt prompt) {
    var options = prompt.getOptions();
    var context =
        options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools
            ? tools.getToolContext()
            : null;
    var value = context == null ? null : context.get(OWNER_CONTEXT_KEY);
    if (!(value instanceof String owner) || owner.isBlank())
      throw new IllegalArgumentException("Responses replay owner is required");
    return owner;
  }
}
