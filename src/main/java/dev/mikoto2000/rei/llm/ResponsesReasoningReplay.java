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
  static final String USAGE_KEY = dev.mikoto2000.rei.modelmessage.ModelTokenUsage.METADATA_KEY;

  private ResponsesReasoningReplay() {}

  static Prompt prepare(Prompt prompt, ResolvedLlmEndpoint endpoint) {
    var context = prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools
        ? tools.getToolContext() : null;
    if (context != null && context.containsKey(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY)) {
      if (!(context.get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY)
          instanceof dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin origin)
          || !origin.endpoint().equals(endpoint.fingerprint()))
        throw new IllegalArgumentException("Responses replay endpoint mismatch");
    }
    String requestOwner =
        endpoint.capabilities().reasoningReplay() == ReasoningReplayMode.ENCRYPTED
            ? owner(prompt)
            : optionalOwner(prompt);
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
    boolean encrypted = endpoint.capabilities().reasoningReplay() == ReasoningReplayMode.ENCRYPTED;
    String requestOwner = encrypted ? owner(prompt) : optionalOwner(prompt);
    var usage = ResponsesTransportException.usage(response.getMetadata().getUsage());
    var generations =
        response.getResults().stream()
            .map(generation -> {
              var output = generation.getOutput();
              var metadata = new java.util.HashMap<String, Object>(output.getMetadata());
              if (requestOwner != null) {
                metadata.put(ORIGIN_OWNER, requestOwner);
                metadata.put(ORIGIN_ENDPOINT, endpoint.fingerprint());
              }
              // Spring's MessageAggregator rebuilds Usage from three totals. Its message
              // metadata is retained, so carry only the normalized numeric details here.
              metadata.put(
                  USAGE_KEY,
                  usage == null
                      ? new dev.mikoto2000.rei.modelmessage.ModelTokenUsage(
                          null, null, null, null, null)
                      : usage);
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
          ResponsesTransportException.usage(response));
    }
  }

  private static String owner(Prompt prompt) {
    String owner = optionalOwner(prompt);
    if (owner == null) throw new IllegalArgumentException("Responses replay owner is required");
    return owner;
  }

  private static String optionalOwner(Prompt prompt) {
    var options = prompt.getOptions();
    var context =
        options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools
            ? tools.getToolContext()
            : null;
    var value = context == null ? null : context.get(OWNER_CONTEXT_KEY);
    if (context != null && context.containsKey(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY)) {
      if (!(context.get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY)
          instanceof dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin origin)
          || (value != null && !origin.owner().equals(value)))
        throw new IllegalArgumentException("Responses replay owner mismatch");
      return origin.owner();
    }
    if (value == null) return null;
    if (!(value instanceof String owner) || owner.isBlank())
      throw new IllegalArgumentException("Responses replay owner is required");
    return owner;
  }
}
