package dev.mikoto2000.rei.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Keeps SDK response metadata as bounded, portable JSON, without teaching storage SDK types. */
final class NamedChatMessages implements ChatModel {
  private static final String SNAPSHOT = "rei.llm.chat-native";
  private static final String ADDITIONAL = "openai.tool_calls.additional_properties";
  private static final Set<String> NATIVE =
      Set.of(
          "id",
          "role",
          "index",
          "finishReason",
          "refusal",
          "annotations",
          "reasoningContent",
          ADDITIONAL);
  private static final ObjectMapper JSON =
      new ObjectMapper(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      com.fasterxml.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(1024 * 1024)
                          .build())
                  .build())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private final ChatModel delegate;
  private final ResolvedLlmEndpoint endpoint;

  NamedChatMessages(ChatModel delegate, ResolvedLlmEndpoint endpoint) {
    this.delegate = delegate;
    this.endpoint = endpoint;
  }

  @Override
  public ChatOptions getOptions() {
    return delegate.getOptions();
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    return canonical(delegate.call(nativeInput(prompt)), prompt);
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(() -> delegate.stream(nativeInput(prompt)))
        .map(response -> canonical(response, prompt));
  }

  private Prompt nativeInput(Prompt prompt) {
    var checked = ResponsesReasoningReplay.prepare(prompt, endpoint);
    var messages =
        checked.getInstructions().stream()
            .map(message -> {
              if (!(message instanceof AssistantMessage assistant)
                  || !assistant.getMetadata().containsKey(SNAPSHOT)) return message;
              var metadata = new HashMap<>(assistant.getMetadata());
              var encoded = metadata.remove(SNAPSHOT);
              if (!(encoded instanceof String text) || text.length() > 1024 * 1024) throw invalid();
              if (metadata.keySet().stream().anyMatch(NATIVE::contains)) throw invalid();
              try {
                var snapshot = JSON.readTree(text);
                validate(snapshot);
                for (var entry : snapshot.properties()) {
                  var value = entry.getValue();
                  metadata.put(
                      entry.getKey(),
                      entry.getKey().equals("index")
                          ? value.longValue()
                          : JSON.convertValue(value, Object.class));
                }
              } catch (java.io.IOException failure) {
                throw invalid();
              }
              return (org.springframework.ai.chat.messages.Message)
                  AssistantMessage.builder()
                      .parts(assistant.getParts())
                      .properties(metadata)
                      .build();
            })
            .toList();
    return new Prompt(messages, checked.getOptions());
  }

  private ChatResponse canonical(ChatResponse response, Prompt prompt) {
    var generations =
        response.getResults().stream()
            .map(generation -> {
              var output = generation.getOutput();
              var metadata = new HashMap<>(output.getMetadata());
              if (metadata.containsKey(SNAPSHOT)) throw invalid();
              var snapshot = JSON.createObjectNode();
              for (String key : NATIVE) {
                if (!metadata.containsKey(key)) continue;
                Object value = metadata.remove(key);
                if (key.equals("finishReason")
                    && value
                        instanceof
                        com.openai.models.chat.completions.ChatCompletion.Choice.FinishReason.Value
                            reason) value = reason.name();
                if (key.equals("annotations")) {
                  if (!(value instanceof List<?> annotations)) throw invalid();
                  var array = snapshot.putArray(key);
                  for (var annotation : annotations) {
                    if (annotation
                        instanceof
                        com.openai.models.chat.completions.ChatCompletionMessage.Annotation
                            nativeAnnotation)
                      array.add(
                          com.openai.core.ObjectMappers.jsonMapper().valueToTree(nativeAnnotation));
                    else if (annotation instanceof Map<?, ?> empty && empty.isEmpty())
                      array.addObject();
                    else throw invalid();
                  }
                } else if (key.equals(ADDITIONAL)) {
                  if (!(value instanceof Map<?, ?> properties) || properties.size() > 128)
                    throw invalid();
                  var object = snapshot.putObject(key);
                  for (var entry : properties.entrySet()) {
                    if (!(entry.getKey() instanceof String id)
                        || id.isBlank()
                        || !(entry.getValue() instanceof String data)) throw invalid();
                    object.put(id, data);
                  }
                } else if (value instanceof String text) snapshot.put(key, text);
                else if (key.equals("index") && value instanceof Long number)
                  snapshot.put(key, number);
                else throw invalid();
              }
              validate(snapshot);
              try {
                String encoded = JSON.writeValueAsString(snapshot);
                if (encoded.length() > 1024 * 1024) throw invalid();
                metadata.put(SNAPSHOT, encoded);
              } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw invalid();
              }
              return new Generation(
                  AssistantMessage.builder().parts(output.getParts()).properties(metadata).build(),
                  generation.getMetadata());
            })
            .toList();
    return ResponsesReasoningReplay.bind(
        new ChatResponse(generations, response.getMetadata()), prompt, endpoint);
  }

  private static void validate(JsonNode snapshot) {
    if (snapshot == null || !snapshot.isObject()) throw invalid();
    for (var entry : snapshot.properties()) {
      String key = entry.getKey();
      var value = entry.getValue();
      if (!NATIVE.contains(key)) throw invalid();
      if (key.equals("index")) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
          throw invalid();
      } else if (key.equals("annotations")) {
        if (!value.isArray() || value.size() > 1024) throw invalid();
        for (var annotation : value) if (!annotation.isObject()) throw invalid();
      } else if (key.equals(ADDITIONAL)) {
        if (!value.isObject() || value.size() > 128) throw invalid();
        for (var property : value.properties())
          if (property.getKey().isBlank() || !property.getValue().isTextual()) throw invalid();
      } else if (!value.isTextual()) throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Unsupported named Chat Completions message metadata");
  }
}
