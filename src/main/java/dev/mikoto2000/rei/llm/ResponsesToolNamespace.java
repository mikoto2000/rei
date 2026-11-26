package dev.mikoto2000.rei.llm;

import com.openai.core.JsonValue;
import com.openai.models.responses.NamespaceTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.Tool;
import java.util.List;
import java.util.Set;

/** Typed SDK adaptation for Rei's locally executed functions; never rewrites HTTP JSON. */
final class ResponsesToolNamespace {
  static final String ATTRIBUTE = "rei.responses.tool-namespace";

  private ResponsesToolNamespace() {}

  static ResponseCreateParams request(ResponseCreateParams request) {
    var tools = request.tools().orElse(List.of());
    if (tools.isEmpty()) return request;
    var functions =
        tools.stream()
            .map(tool -> {
              var function =
                  tool.function()
                      .orElseThrow(
                          () -> new IllegalArgumentException("Plan requires local function tools"));
              if (!Set.of("type", "name", "description", "parameters", "strict")
                  .containsAll(function._allProperties().keySet()))
                throw new IllegalArgumentException("Unsupported Plan function definition");
              return NamespaceTool.Tool.ofFunction(
                  NamespaceTool.Tool.Function.builder()
                      .name(function.name())
                      .description(function._description())
                      .parameters(
                          JsonValue.from(
                              function
                                  .parameters()
                                  .orElseThrow(() ->
                                      new IllegalArgumentException(
                                          "Plan function parameters are required"))
                                  ._allProperties()))
                      .strict(function._strict())
                      .build());
            })
            .toList();
    return request.toBuilder()
        .tools(
            List.of(
                Tool.ofNamespace(
                    NamespaceTool.builder()
                        .name("rei")
                        .description("Locally executed Rei tools")
                        .tools(functions)
                        .build())))
        .build();
  }

  /** One exchange owns the exact sent definitions and native calls; no ambient name resolution. */
  static final class Exchange {
    private final java.util.Map<String, org.springframework.ai.chat.messages.part.ToolCallPart>
        history = new java.util.LinkedHashMap<>();
    private volatile Set<String> declared = Set.of();
    private final java.util.Map<String, com.openai.models.responses.ResponseFunctionToolCall>
        received = new java.util.concurrent.ConcurrentHashMap<>();

    Exchange(org.springframework.ai.chat.prompt.Prompt prompt, ResolvedLlmEndpoint endpoint) {
      var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions();
      var context = options.getToolContext();
      Object owner = context.get(ResponsesReasoningReplay.OWNER_CONTEXT_KEY);
      if (context.get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY)
          instanceof dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin origin)
        owner = origin.owner();
      var identity = ResponsesReasoningReplay.identity(prompt.getOptions(), endpoint);
      for (var message : prompt.getInstructions()) {
        if (!(message instanceof org.springframework.ai.chat.messages.AssistantMessage assistant))
          continue;
        for (var part : assistant.getParts()) {
          if (!(part instanceof org.springframework.ai.chat.messages.part.ToolCallPart call))
            continue;
          if (!validCallId(call.toolCall().id())
              || !"rei".equals(call.attributes().get(ATTRIBUTE))
              || owner == null
              || !owner.equals(assistant.getMetadata().get(ResponsesReasoningReplay.ORIGIN_OWNER))
              || !identity.equals(
                  assistant.getMetadata().get(ResponsesReasoningReplay.ORIGIN_ENDPOINT))
              || history.putIfAbsent(call.toolCall().id(), call) != null)
            throw new IllegalArgumentException("Plan tool replay namespace or origin mismatch");
        }
      }
    }

    ResponseCreateParams prepare(ResponseCreateParams request) {
      var names = new java.util.HashSet<String>();
      for (var tool : request.tools().orElse(List.of())) {
        var namespace = tool.namespace().orElseThrow(Exchange::invalid);
        if (!"rei".equals(namespace.name())) throw invalid();
        for (var nested : namespace.tools()) {
          var function = nested.function().orElseThrow(Exchange::invalid);
          if (!names.add(function.name())) throw invalid();
        }
      }
      declared = Set.copyOf(names);
      var calls = new java.util.HashSet<String>();
      var results = new java.util.HashSet<String>();
      var input =
          request.input().orElseThrow(Exchange::invalid).asResponse().stream()
              .map(item -> {
                if (item.isFunctionCall()) {
                  var call = item.asFunctionCall();
                  var original = history.get(call.callId());
                  if (original == null
                      || !calls.add(call.callId())
                      || !original.toolCall().name().equals(call.name())
                      || !original.toolCall().arguments().equals(call.arguments())
                      || !java.util.Objects.equals(
                          original
                              .attributes()
                              .get(
                                  org.springframework.ai.openai.responses.OpenAiResponsesMetadata
                                      .ITEM_ID_ATTRIBUTE),
                          call.id().orElse(null))) throw invalid();
                  return com.openai.models.responses.ResponseInputItem.ofFunctionCall(
                      call.toBuilder().namespace("rei").build());
                }
                if (item.isFunctionCallOutput()) {
                  var result = item.asFunctionCallOutput();
                  String id = result.callId().orElseThrow(Exchange::invalid);
                  if (!history.containsKey(id) || !results.add(id)) throw invalid();
                  return com.openai.models.responses.ResponseInputItem.ofFunctionCallOutput(
                      result.toBuilder().namespace("rei").build());
                }
                return item;
              })
              .toList();
      if (!calls.equals(history.keySet()) || !results.equals(calls)) throw invalid();
      return request.toBuilder().inputOfResponse(input).build();
    }

    void accept(com.openai.models.responses.ResponseOutputItem item, boolean complete) {
      if (!item.isFunctionCall()) return;
      var call = item.asFunctionCall();
      if (!Set.of("type", "id", "call_id", "name", "namespace", "arguments", "status")
              .containsAll(call._allProperties().keySet())
          || !"rei".equals(call.namespace().orElse(null))
          || !declared.contains(call.name())
          || !validCallId(call.callId())
          || history.containsKey(call.callId())) throw invalid();
      if (complete) {
        var previous = received.putIfAbsent(call.callId(), call);
        if (previous != null && !previous.equals(call)) throw invalid();
      }
    }

    org.springframework.ai.chat.model.ChatResponse bind(
        org.springframework.ai.chat.model.ChatResponse response) {
      var generations =
          response.getResults().stream()
              .map(generation -> {
                var assistant = generation.getOutput();
                var parts =
                    assistant.getParts().stream()
                        .map(part -> {
                          if (!(part
                                  instanceof
                                  org.springframework.ai.chat.messages.part.ToolCallPart call)
                              || org.springframework.ai.chat.messages.part.StreamingParts.isPartial(
                                  part)) return part;
                          var nativeCall = received.get(call.toolCall().id());
                          if (nativeCall == null
                              || !nativeCall.name().equals(call.toolCall().name())
                              || !nativeCall.arguments().equals(call.toolCall().arguments())
                              || !java.util.Objects.equals(
                                  nativeCall.id().orElse(null),
                                  call.attributes()
                                      .get(
                                          org.springframework.ai.openai.responses
                                              .OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE)))
                            throw invalid();
                          var attributes = new java.util.HashMap<>(part.attributes());
                          attributes.put(ATTRIBUTE, "rei");
                          return (org.springframework.ai.chat.messages.part.MessagePart)
                              new org.springframework.ai.chat.messages.part.ToolCallPart(
                                  call.toolCall(), call.payload(), attributes);
                        })
                        .toList();
                return new org.springframework.ai.chat.model.Generation(
                    org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .parts(parts)
                        .properties(assistant.getMetadata())
                        .build(),
                    generation.getMetadata());
              })
              .toList();
      return new org.springframework.ai.chat.model.ChatResponse(
          generations, response.getMetadata());
    }

    private static ResponsesTransportException invalid() {
      return new ResponsesTransportException(ResponsesTransportException.Category.INVALID_RESPONSE);
    }

    private static boolean validCallId(String id) {
      return id != null
          && !id.isEmpty()
          && id.length() <= 512
          && id.codePoints()
              .noneMatch(value ->
                  Character.isISOControl(value)
                      || Character.isWhitespace(value)
                      || Character.isSpaceChar(value));
    }
  }
}
