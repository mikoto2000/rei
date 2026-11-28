package dev.mikoto2000.rei.modelmessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mikoto2000.rei.tooldelivery.ToolResultMetadata;
import java.util.HashMap;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;

/** Versioned model-message serialization, independent of transport, storage and execution. */
public final class ModelMessageCodec {
  public static final int MAX_BYTES = 8 * 1024 * 1024;

  /** Whether the legacy text/call representation would lose model protocol information. */
  public static boolean requiresPayload(Message message) {
    if (message instanceof UserMessage user) return !user.getMedia().isEmpty();
    if (!(message instanceof AssistantMessage assistant)) return false;
    if (assistant.getMetadata().containsKey("rei.llm.replay-origin-owner")
        || assistant.getMetadata().containsKey("rei.llm.replay-origin-endpoint")
        || assistant.getMetadata().containsKey(ModelTokenUsage.METADATA_KEY)) return true;
    return assistant.getParts().stream()
        .anyMatch(part -> {
          if (!part.attributes().isEmpty()) return true;
          if (part instanceof TextPart text) return text.payload() != null;
          if (part instanceof ToolCallPart call) return call.payload() != null;
          return true;
        });
  }

  private static final Set<String> METADATA =
      Set.of(
          "source",
          "sourceId",
          "rei.llm.chat-native",
          "rei.llm.replay-origin-owner",
          "rei.llm.replay-origin-endpoint",
          "rei.historySequence",
          "JdbcChatMemoryRepository_message_timestamp",
          "rei.reviewGuidance",
          "rei.progressFeedback",
          "rei.resumeWorkReminder",
          "rei.resumeContext",
          "rei.knownFileInventory",
          "rei.workingSet",
          "rei.workContext",
          "rei.longTermMemory",
          "rei.legacyHistory",
          "rei.outputLimitHandoff",
          "rei.resumeRunId",
          "rei.historyGeneration",
          "rei.reviewBudgetRecovery",
          "rei.fileBudgetRecovery",
          ModelTokenUsage.METADATA_KEY);
  private static final ObjectMapper JSON =
      new ObjectMapper(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      com.fasterxml.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(MAX_BYTES)
                          .build())
                  .build())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  public byte[] encode(Message message) {
    if (!(message instanceof AssistantMessage
        || message instanceof ToolResponseMessage
        || message instanceof UserMessage
        || message instanceof SystemMessage)) throw invalid();
    var root =
        JSON.createObjectNode()
            .put("schemaVersion", 1)
            .put("role", message.getMessageType().name().toLowerCase(java.util.Locale.ROOT));
    var parts = root.putArray("parts");
    if (message instanceof AssistantMessage assistant) {
      if (assistant.getParts().size() > 1024) throw invalid();
      var ids = new java.util.HashSet<String>();
      assistant
          .getParts()
          .forEach(part -> {
            var encoded = encodePart(part);
            if (part instanceof ToolCallPart call && !ids.add(call.toolCall().id()))
              throw invalid();
            parts.add(encoded);
          });
    } else if (message instanceof ToolResponseMessage tool) {
      if (tool.getResponses().size() > 1024) throw invalid();
      var ids = new java.util.HashSet<String>();
      for (var response : tool.getResponses()) {
        var part =
            parts
                .addObject()
                .put("type", "tool_result")
                .put("callId", response.id())
                .put("name", response.name())
                .put("data", response.responseData());
        decodeResult(part);
        if (!ids.add(response.id())) throw invalid();
      }
    } else {
      parts.add(encodePart(TextPart.of(message.getText())));
      if (message instanceof UserMessage user) {
        if (user.getMedia().size() > 1023) throw invalid();
        user.getMedia().forEach(media -> parts.add(encodeMedia(media)));
      }
    }
    var metadata = root.putObject("metadata");
    if (message instanceof ToolResponseMessage tool) metadata.set("delivery", encodeDelivery(tool));
    for (var entry : new java.util.TreeMap<>(message.getMetadata()).entrySet()) {
      if (entry.getKey().equals("messageType")) continue;
      if (message instanceof ToolResponseMessage
          && Set.of(
                  "rei.deliveryPolicies",
                  "rei.losslessPages",
                  "rei.reviewReceipts",
                  "rei.fileReadBudgets")
              .contains(entry.getKey())) continue;
      metadata.set(entry.getKey(), encodeProperty(entry.getKey(), entry.getValue()));
    }
    try {
      byte[] bytes = JSON.writeValueAsBytes(root);
      if (bytes.length > MAX_BYTES) throw invalid();
      return bytes;
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw invalid();
    }
  }

  public Message decode(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_BYTES) throw invalid();
    try {
      var root = JSON.readTree(bytes);
      if (root == null) throw invalid();
      if (root.has("kind") && !root.has("schemaVersion")) return decodeLegacy(root);
      fields(root, Set.of("schemaVersion", "role", "parts", "metadata"));
      if (!root.path("schemaVersion").isInt() || root.path("schemaVersion").intValue() != 1)
        throw invalid();
      String role = required(root, "role");
      if (!Set.of("assistant", "tool", "user", "system").contains(role)) throw invalid();
      if (!root.path("parts").isArray() || root.path("parts").size() > 1024) throw invalid();
      var parts = new java.util.ArrayList<MessagePart>();
      var media = new java.util.ArrayList<org.springframework.ai.content.Media>();
      var results = new java.util.ArrayList<ToolResponseMessage.ToolResponse>();
      var ids = new java.util.HashSet<String>();
      for (var part : root.path("parts")) {
        if (role.equals("user") && "media".equals(part.path("type").asText())) {
          if (parts.size() != 1) throw invalid();
          media.add(decodeMedia(part));
        } else if (!role.equals("tool")) {
          if (!media.isEmpty()) throw invalid();
          var decoded = decodePart(part);
          if (decoded instanceof ToolCallPart call && !ids.add(call.toolCall().id()))
            throw invalid();
          parts.add(decoded);
        } else {
          var result = decodeResult(part);
          if (!ids.add(result.id())) throw invalid();
          results.add(result);
        }
      }
      var metadata = new HashMap<String, Object>();
      var allowed = new java.util.HashSet<>(METADATA);
      if (role.equals("tool")) allowed.add("delivery");
      fields(root.path("metadata"), allowed);
      root.path("metadata")
          .properties()
          .forEach(entry -> {
            if (entry.getKey().equals("delivery")) return;
            metadata.put(entry.getKey(), decodeProperty(entry.getKey(), entry.getValue()));
          });
      if (role.equals("tool")) {
        var raw = root.path("metadata").get("delivery");
        if (raw != null) {
          metadata.putAll(decodeDelivery(raw, ids).restore());
        }
        return ToolResponseMessage.builder().responses(results).metadata(metadata).build();
      }
      if (!role.equals("assistant")) {
        if (parts.size() != 1
            || !(parts.getFirst() instanceof TextPart text)
            || text.payload() != null
            || !text.attributes().isEmpty()) throw invalid();
        return role.equals("user")
            ? UserMessage.builder().text(text.text()).media(media).metadata(metadata).build()
            : SystemMessage.builder().text(text.text()).metadata(metadata).build();
      }
      return AssistantMessage.builder().parts(parts).properties(metadata).build();
    } catch (java.io.IOException | IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static Message decodeLegacy(JsonNode root) {
    fields(root, Set.of("kind", "text", "calls", "responses", "delivery"));
    String kind = required(root, "kind");
    String text = java.util.Objects.toString(text(root, "text"), "");
    var calls = new java.util.ArrayList<AssistantMessage.ToolCall>();
    var responses = new java.util.ArrayList<ToolResponseMessage.ToolResponse>();
    var callIds = new java.util.HashSet<String>();
    var responseIds = new java.util.HashSet<String>();
    for (var call : legacyArray(root, "calls")) {
      fields(call, Set.of("id", "type", "name", "arguments"));
      String id = required(call, "id"), name = required(call, "name");
      if (id.isBlank() || name.isBlank() || !callIds.add(id)) throw invalid();
      calls.add(
          new AssistantMessage.ToolCall(
              id, required(call, "type"), name, required(call, "arguments")));
    }
    for (var result : legacyArray(root, "responses")) {
      fields(result, Set.of("id", "name", "responseData"));
      String id = required(result, "id"), name = required(result, "name");
      if (id.isBlank() || name.isBlank() || !responseIds.add(id)) throw invalid();
      responses.add(
          new ToolResponseMessage.ToolResponse(id, name, required(result, "responseData")));
    }
    var delivery = root.get("delivery");
    boolean hasDelivery = delivery != null && !delivery.isNull();
    return switch (kind) {
      case "ASSISTANT" -> {
        if (!responses.isEmpty() || hasDelivery) throw invalid();
        yield AssistantMessage.builder().content(text).toolCalls(calls).build();
      }
      case "USER" -> {
        if (!calls.isEmpty() || !responses.isEmpty() || hasDelivery) throw invalid();
        yield new UserMessage(text);
      }
      case "TOOL" -> {
        if (!calls.isEmpty() || !text.isEmpty()) throw invalid();
        yield ToolResponseMessage.builder()
            .responses(responses)
            .metadata(
                hasDelivery ? decodeDelivery(delivery, responseIds).restore() : java.util.Map.of())
            .build();
      }
      default -> throw invalid();
    };
  }

  private static Iterable<JsonNode> legacyArray(JsonNode root, String key) {
    var node = root.get(key);
    if (node == null || node.isNull()) return java.util.List.of();
    if (!node.isArray() || node.size() > 1024) throw invalid();
    return node;
  }

  private static ObjectNode encodeMedia(org.springframework.ai.content.Media media) {
    var node =
        JSON.createObjectNode()
            .put("type", "media")
            .put("mimeType", media.getMimeType().toString())
            .put("id", media.getId())
            .put("name", media.getName());
    var data = media.getData();
    if (data instanceof byte[] bytes) {
      if (bytes.length > MAX_BYTES) throw invalid();
      node.put("dataType", "bytes")
          .put("data", java.util.Base64.getEncoder().encodeToString(bytes));
    } else if (data instanceof String value) node.put("dataType", "string").put("data", value);
    else throw invalid();
    decodeMedia(node);
    return node;
  }

  private static org.springframework.ai.content.Media decodeMedia(JsonNode node) {
    fields(node, Set.of("type", "mimeType", "id", "name", "dataType", "data"));
    if (!"media".equals(required(node, "type"))) throw invalid();
    var builder =
        org.springframework.ai.content.Media.builder()
            .mimeType(org.springframework.util.MimeType.valueOf(required(node, "mimeType")));
    String data = required(node, "data");
    switch (required(node, "dataType")) {
      case "bytes" -> builder.data(java.util.Base64.getDecoder().decode(data));
      case "string" -> builder.data(data);
      default -> throw invalid();
    }
    String id = text(node, "id"), name = text(node, "name");
    if (id != null) builder.id(id);
    if (name != null) builder.name(name);
    return builder.build();
  }

  private static JsonNode encodeProperty(String key, Object value) {
    if (!METADATA.contains(key)) throw invalid();
    if (key.equals(ModelTokenUsage.METADATA_KEY)) {
      if (!(value instanceof ModelTokenUsage)) throw invalid();
      JsonNode node = JSON.valueToTree(value);
      decodeProperty(key, node);
      return node;
    }
    if (key.equals("JdbcChatMemoryRepository_message_timestamp")) {
      if (!(value instanceof java.time.Instant instant)) throw invalid();
      return JSON.getNodeFactory().textNode(instant.toString());
    }
    JsonNode node;
    if (value instanceof String text) node = JSON.getNodeFactory().textNode(text);
    else if (value instanceof Boolean flag) node = JSON.getNodeFactory().booleanNode(flag);
    else if (value instanceof Long number) node = JSON.getNodeFactory().numberNode(number);
    else if (value instanceof Integer number) node = JSON.getNodeFactory().numberNode(number);
    else throw invalid();
    decodeProperty(key, node);
    return node;
  }

  private static Object decodeProperty(String key, JsonNode value) {
    return switch (key) {
      case ModelTokenUsage.METADATA_KEY -> {
        fields(value, Set.of("input", "output", "total", "cached", "reasoning"));
        yield new ModelTokenUsage(
            number(value, "input"),
            number(value, "output"),
            number(value, "total"),
            number(value, "cached"),
            number(value, "reasoning"));
      }
      case "source",
          "sourceId",
          "rei.llm.replay-origin-owner",
          "rei.llm.replay-origin-endpoint",
          "rei.llm.chat-native",
          "rei.resumeRunId",
          "rei.historyGeneration" -> {
        if (!value.isTextual()) throw invalid();
        yield value.textValue();
      }
      case "rei.historySequence" -> {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
          throw invalid();
        yield value.longValue();
      }
      case "rei.reviewBudgetRecovery",
          "rei.fileBudgetRecovery",
          "rei.reviewGuidance",
          "rei.progressFeedback",
          "rei.resumeWorkReminder",
          "rei.resumeContext",
          "rei.knownFileInventory",
          "rei.workingSet",
          "rei.workContext",
          "rei.longTermMemory",
          "rei.legacyHistory",
          "rei.outputLimitHandoff" -> {
        if (!value.isBoolean()) throw invalid();
        yield value.booleanValue();
      }
      case "JdbcChatMemoryRepository_message_timestamp" -> {
        if (!value.isTextual()) throw invalid();
        try {
          yield java.time.Instant.parse(value.textValue());
        } catch (java.time.format.DateTimeParseException malformed) {
          throw invalid();
        }
      }
      default -> throw invalid();
    };
  }

  private static Long number(JsonNode node, String key) {
    var value = node.get(key);
    if (value == null || value.isNull()) return null;
    if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
    return value.longValue();
  }

  private static JsonNode encodeDelivery(ToolResponseMessage tool) {
    var captured = ToolResultMetadata.capture(tool);
    var counts =
        java.util.Map.of(
            "rei.deliveryPolicies",
            captured.policies().size(),
            "rei.losslessPages",
            captured.pages().size(),
            "rei.reviewReceipts",
            captured.receipts().size(),
            "rei.fileReadBudgets",
            captured.fileReadBudgets().size());
    counts.forEach((key, count) -> {
      var value = tool.getMetadata().get(key);
      if (value != null
          && (!(value instanceof java.util.Map<?, ?> values) || values.size() != count))
        throw invalid();
    });
    JsonNode node = JSON.valueToTree(captured);
    decodeDelivery(
        node,
        tool.getResponses().stream()
            .map(ToolResponseMessage.ToolResponse::id)
            .collect(java.util.stream.Collectors.toSet()));
    return node;
  }

  private static ToolResultMetadata decodeDelivery(JsonNode node, Set<String> ids) {
    fields(node, Set.of("policies", "pages", "receipts", "fileReadBudgets"));
    var policies = new HashMap<String, String>();
    var pages = new HashMap<String, ToolResultMetadata.Page>();
    var receipts = new HashMap<String, ToolResultMetadata.Receipt>();
    var budgets = new HashMap<String, ToolResultMetadata.FileReadBudget>();
    for (var group : node.properties()) {
      if (!group.getValue().isObject()) throw invalid();
      for (var entry : group.getValue().properties()) {
        String id = entry.getKey();
        if (!ids.contains(id)) throw invalid();
        var value = entry.getValue();
        switch (group.getKey()) {
          case "policies" -> {
            if (!value.isTextual() || !value.textValue().matches("[A-Z][A-Z0-9_]{0,127}"))
              throw invalid();
            policies.put(id, value.textValue());
          }
          case "pages" -> {
            fields(value, Set.of("ref", "offset"));
            String ref = required(value, "ref");
            if (ref.isBlank() || ref.length() > 4096) throw invalid();
            pages.put(id, new ToolResultMetadata.Page(ref, nonnegative(value, "offset")));
          }
          case "receipts" -> {
            fields(value, Set.of("serializedHash"));
            String hash = required(value, "serializedHash");
            if (!hash.matches("[0-9a-f]{64}")) throw invalid();
            receipts.put(id, new ToolResultMetadata.Receipt(hash));
          }
          case "fileReadBudgets" -> {
            fields(value, Set.of("tokens"));
            int tokens = nonnegative(value, "tokens");
            if (tokens == 0) throw invalid();
            budgets.put(id, new ToolResultMetadata.FileReadBudget(tokens));
          }
          default -> throw invalid();
        }
      }
    }
    return new ToolResultMetadata(policies, pages, receipts, budgets);
  }

  private static int nonnegative(JsonNode node, String key) {
    var value = node.path(key);
    if (!value.isInt() || value.intValue() < 0) throw invalid();
    return value.intValue();
  }

  private static ToolResponseMessage.ToolResponse decodeResult(JsonNode node) {
    fields(node, Set.of("type", "callId", "name", "data"));
    if (!"tool_result".equals(required(node, "type"))) throw invalid();
    String id = required(node, "callId"), name = required(node, "name");
    if (id.isBlank() || name.isBlank()) throw invalid();
    return new ToolResponseMessage.ToolResponse(id, name, required(node, "data"));
  }

  private static ObjectNode encodePart(MessagePart part) {
    if (part.attributes().containsKey(StreamingParts.PART_INDEX_ATTRIBUTE)
        || part.attributes().containsKey(StreamingParts.PARTIAL_ATTRIBUTE)) throw invalid();
    var node = JSON.createObjectNode();
    if (part instanceof TextPart text) node.put("type", "text").put("text", text.text());
    else if (part instanceof ReasoningPart reasoning)
      node.put("type", "reasoning")
          .put("text", reasoning.text())
          .put("summary", reasoning.summary());
    else if (part instanceof ToolCallPart call) {
      node.put("type", "tool_call");
      var value = call.toolCall();
      node.putObject("call")
          .put("id", value.id())
          .put("type", value.type())
          .put("name", value.name())
          .put("arguments", value.arguments());
    } else throw invalid();
    node.set("attributes", JSON.valueToTree(part.attributes()));
    OpaquePayload payload =
        part instanceof TextPart text
            ? text.payload()
            : part instanceof ReasoningPart reasoning
                ? reasoning.payload()
                : ((ToolCallPart) part).payload();
    if (payload != null)
      node.putObject("payload")
          .put("provider", payload.provider())
          .put("kind", payload.kind())
          .put("data", payload.data());
    decodePart(node);
    return node;
  }

  private static MessagePart decodePart(JsonNode node) {
    String type = required(node, "type");
    fields(
        node,
        switch (type) {
          case "text" -> Set.of("type", "text", "payload", "attributes");
          case "reasoning" -> Set.of("type", "text", "summary", "payload", "attributes");
          case "tool_call" -> Set.of("type", "call", "payload", "attributes");
          default -> throw invalid();
        });
    var attributes = new HashMap<String, String>();
    var rawAttributes = node.path("attributes");
    if (!rawAttributes.isObject() || rawAttributes.size() > 64) throw invalid();
    rawAttributes
        .properties()
        .forEach(entry -> {
          if (!entry.getValue().isTextual()
              || entry.getKey().length() > 128
              || entry.getKey().equals(StreamingParts.PART_INDEX_ATTRIBUTE)
              || entry.getKey().equals(StreamingParts.PARTIAL_ATTRIBUTE)) throw invalid();
          attributes.put(entry.getKey(), entry.getValue().textValue());
        });
    var raw = node.get("payload");
    if (raw != null && !raw.isNull()) fields(raw, Set.of("provider", "kind", "data"));
    var payload =
        raw == null || raw.isNull()
            ? null
            : new OpaquePayload(
                required(raw, "provider"), required(raw, "kind"), required(raw, "data"));
    return switch (type) {
      case "text" -> new TextPart(required(node, "text"), payload, attributes);
      case "reasoning" ->
          new ReasoningPart(text(node, "text"), text(node, "summary"), payload, attributes);
      case "tool_call" -> {
        var call = node.path("call");
        fields(call, Set.of("id", "type", "name", "arguments"));
        String id = required(call, "id"), name = required(call, "name");
        if (id.isBlank() || name.isBlank()) throw invalid();
        yield new ToolCallPart(
            new AssistantMessage.ToolCall(
                id, required(call, "type"), name, required(call, "arguments")),
            payload,
            attributes);
      }
      default -> throw invalid();
    };
  }

  private static String text(JsonNode node, String field) {
    var value = node.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()) throw invalid();
    return value.textValue();
  }

  private static String required(JsonNode node, String field) {
    var value = text(node, field);
    if (value == null) throw invalid();
    return value;
  }

  private static void fields(JsonNode node, Set<String> allowed) {
    if (node == null || !node.isObject()) throw invalid();
    if (node.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey())))
      throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid model message schema");
  }
}
