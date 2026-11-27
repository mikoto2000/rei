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

  static String identity(
      org.springframework.ai.chat.prompt.ChatOptions options, ResolvedLlmEndpoint endpoint) {
    if (options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools) {
      var context = tools.getToolContext();
      var key = dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY;
      if (context != null && context.containsKey(key)) {
        if (!(context.get(key) instanceof RunLlmConnection connection)
            || !connection.endpoint().equals(endpoint))
          throw new IllegalArgumentException("Responses accepted connection mismatch");
        return connection.fingerprint();
      }
    }
    return endpoint.fingerprint();
  }

  static Prompt prepare(Prompt prompt, ResolvedLlmEndpoint endpoint) {
    var identity = identity(prompt.getOptions(), endpoint);
    var context =
        prompt.getOptions()
                instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools
            ? tools.getToolContext()
            : null;
    if (context != null
        && context.containsKey(
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY)) {
      if (!(context.get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY)
              instanceof dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin origin)
          || !origin.endpoint().equals(identity))
        throw new IllegalArgumentException("Responses replay endpoint mismatch");
    }
    String requestOwner =
        endpoint.capabilities().reasoningReplay() != ReasoningReplayMode.NONE
            ? owner(prompt)
            : optionalOwner(prompt);
    for (var message : prompt.getInstructions()) {
      if (!(message instanceof AssistantMessage assistant)) continue;
      var metadata = assistant.getMetadata();
      boolean ownerPermitted = false;
      if (metadata.containsKey(ORIGIN_OWNER) || metadata.containsKey(ORIGIN_ENDPOINT)) {
        if (!(metadata.get(ORIGIN_OWNER) instanceof String originOwner)
            || !(metadata.get(ORIGIN_ENDPOINT) instanceof String originEndpoint))
          throw new IllegalArgumentException("Responses replay origin is required");
        var authority =
            context == null
                ? null
                : context.get(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore
                        .HISTORY_GRANT_CONTEXT_KEY);
        ownerPermitted =
            originOwner.equals(requestOwner)
                || (authority
                        instanceof
                        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HistoryReplayGrant
                            grant
                    && grant.permits(assistant, requestOwner, identity));
        if (!ownerPermitted) throw new IllegalArgumentException("Responses replay owner mismatch");
        if (!originEndpoint.equals(identity))
          throw new IllegalArgumentException("Responses replay endpoint mismatch");
      }
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
        if (endpoint.apiType() == LlmApiType.CHAT_COMPLETIONS) {
          var text = reasoning.text();
          var summary = reasoning.summary();
          if (!ownerPermitted
              || reasoning.payload() != null
              || !reasoning.attributes().isEmpty()
              || text == null
              || text.isBlank()
              || text.length() > 4_194_304
              || summary != null && !summary.isBlank())
            throw new IllegalArgumentException("Chat Completions reasoning is incompatible");
          continue;
        }
        if (endpoint.capabilities().reasoningReplay() == ReasoningReplayMode.NONE)
          throw new IllegalArgumentException("Responses reasoning replay mode is incompatible");
        var payload = reasoning.payload();
        if (endpoint.capabilities().reasoningReplay() == ReasoningReplayMode.PLAINTEXT) {
          var text = reasoning.text();
          var summary = reasoning.summary();
          if (!java.util.Set.of(
                      OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE,
                      OpenAiResponsesMetadata.ITEM_STATUS_ATTRIBUTE)
                  .containsAll(reasoning.attributes().keySet())
              || payload != null
              || text == null
              || text.isBlank()
              || text.length() > 4_194_304
              || summary != null && !summary.isBlank())
            throw new IllegalArgumentException("Responses plaintext reasoning is incompatible");
        } else {
          if (payload == null || payload.data().isBlank())
            throw new IllegalArgumentException("Responses reasoning payload is required");
          if (!OpenAiResponsesMetadata.PROVIDER.equals(payload.provider())
              || !OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND.equals(payload.kind()))
            throw new IllegalArgumentException("Responses reasoning payload is incompatible");
        }
        var itemId = reasoning.attributes().get(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE);
        if (itemId == null || itemId.isBlank())
          throw new IllegalArgumentException("Responses reasoning item id is required");
        if (!(metadata.get(ORIGIN_OWNER) instanceof String)
            || !(metadata.get(ORIGIN_ENDPOINT) instanceof String originEndpoint))
          throw new IllegalArgumentException("Responses reasoning replay origin is required");
        if (!ownerPermitted)
          throw new IllegalArgumentException("Responses reasoning replay owner mismatch");
        if (!originEndpoint.equals(identity))
          throw new IllegalArgumentException("Responses reasoning replay endpoint mismatch");
      }
    }
    if (endpoint.capabilities().reasoningReplay() != ReasoningReplayMode.PLAINTEXT) return prompt;
    var messages =
        prompt.getInstructions().stream()
            .map(message -> {
              if (!(message instanceof AssistantMessage assistant)) return message;
              var parts =
                  assistant.getParts().stream()
                      .map(part -> {
                        if (!(part instanceof ReasoningPart reasoning)) return part;
                        var item = new java.util.LinkedHashMap<String, Object>();
                        item.put("type", "reasoning");
                        item.put("summary", java.util.List.of());
                        item.put(
                            "id",
                            reasoning.attributes().get(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE));
                        item.put(
                            "content",
                            java.util.List.of(
                                java.util.Map.of(
                                    "type", "reasoning_text", "text", reasoning.text())));
                        var status =
                            reasoning
                                .attributes()
                                .get(OpenAiResponsesMetadata.ITEM_STATUS_ATTRIBUTE);
                        if (status != null) item.put("status", status);
                        try {
                          String json =
                              new com.fasterxml.jackson.databind.ObjectMapper()
                                  .writeValueAsString(item);
                          return (org.springframework.ai.chat.messages.part.MessagePart)
                              new org.springframework.ai.chat.messages.part.UnknownPart(
                                  OpenAiResponsesMetadata.PROVIDER,
                                  "reasoning",
                                  json,
                                  null,
                                  java.util.Map.of());
                        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                          throw new IllegalArgumentException(
                              "Responses plaintext send view is invalid");
                        }
                      })
                      .toList();
              return (org.springframework.ai.chat.messages.Message)
                  AssistantMessage.builder()
                      .parts(parts)
                      .properties(assistant.getMetadata())
                      .build();
            })
            .toList();
    return new Prompt(messages, prompt.getOptions());
  }

  static org.springframework.ai.chat.model.ChatResponse bind(
      org.springframework.ai.chat.model.ChatResponse response,
      Prompt prompt,
      ResolvedLlmEndpoint endpoint) {
    var identity = identity(prompt.getOptions(), endpoint);
    boolean protectedReplay = endpoint.capabilities().reasoningReplay() != ReasoningReplayMode.NONE;
    String requestOwner = protectedReplay ? owner(prompt) : optionalOwner(prompt);
    var usage = ResponsesTransportException.usage(response.getMetadata().getUsage());
    var generations =
        response.getResults().stream()
            .map(generation -> {
              var output = generation.getOutput();
              var metadata = new java.util.HashMap<String, Object>(output.getMetadata());
              if (requestOwner != null) {
                metadata.put(ORIGIN_OWNER, requestOwner);
                metadata.put(ORIGIN_ENDPOINT, identity);
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
    if (context != null
        && context.containsKey(
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY)) {
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
