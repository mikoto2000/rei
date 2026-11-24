package dev.mikoto2000.rei.modelmessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import dev.mikoto2000.rei.tooldelivery.ToolResultMetadata;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.StreamingParts;

/** Versioned model-message serialization, independent of transport, storage and execution. */
public final class ModelMessageCodec {
  public static final int MAX_BYTES = 8 * 1024 * 1024;
  private static final Set<String> METADATA = Set.of("rei.llm.replay-origin-owner", "rei.llm.replay-origin-endpoint");
  private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
      .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
          .maxNestingDepth(32).maxStringLength(MAX_BYTES).build()).build())
      .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public byte[] encode(Message message) {
    if (!(message instanceof AssistantMessage || message instanceof ToolResponseMessage)) throw invalid();
    var root = JSON.createObjectNode().put("schemaVersion", 1)
        .put("role", message instanceof AssistantMessage ? "assistant" : "tool");
    var parts = root.putArray("parts");
    if (message instanceof AssistantMessage assistant) {
      if (assistant.getParts().size() > 1024) throw invalid();
      assistant.getParts().forEach(part -> parts.add(encodePart(part)));
    } else {
      var tool = (ToolResponseMessage) message;
      if (tool.getResponses().size() > 1024) throw invalid();
      for (var response : tool.getResponses()) {
        var part = parts.addObject().put("type", "tool_result").put("callId", response.id())
            .put("name", response.name()).put("data", response.responseData());
        decodeResult(part);
      }
    }
    var metadata = root.putObject("metadata");
    if (message instanceof ToolResponseMessage tool)
      metadata.set("delivery", JSON.valueToTree(ToolResultMetadata.capture(tool)));
    for (var entry : message.getMetadata().entrySet()) {
      if (entry.getKey().equals("messageType")) continue;
      if (message instanceof ToolResponseMessage && Set.of("rei.deliveryPolicies", "rei.losslessPages",
          "rei.reviewReceipts", "rei.fileReadBudgets").contains(entry.getKey())) continue;
      if (!METADATA.contains(entry.getKey()) || !(entry.getValue() instanceof String)) throw invalid();
      metadata.put(entry.getKey(), (String) entry.getValue());
    }
    try {
      byte[] bytes = JSON.writeValueAsBytes(root);
      if (bytes.length > MAX_BYTES) throw invalid();
      return bytes;
    }
    catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw invalid(); }
  }

  public Message decode(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_BYTES) throw invalid();
    try {
      var root = JSON.readTree(bytes);
      fields(root, Set.of("schemaVersion", "role", "parts", "metadata"));
      if (!root.path("schemaVersion").isInt() || root.path("schemaVersion").intValue() != 1) throw invalid();
      String role = required(root, "role");
      if (!role.equals("assistant") && !role.equals("tool")) throw invalid();
      if (!root.path("parts").isArray() || root.path("parts").size() > 1024) throw invalid();
      var parts = new java.util.ArrayList<MessagePart>();
      var results = new java.util.ArrayList<ToolResponseMessage.ToolResponse>();
      var ids = new java.util.HashSet<String>();
      for (var part : root.path("parts")) {
        if (role.equals("assistant")) parts.add(decodePart(part));
        else {
          var result = decodeResult(part);
          if (!ids.add(result.id())) throw invalid();
          results.add(result);
        }
      }
      var metadata = new HashMap<String, Object>();
      var allowed = new java.util.HashSet<>(METADATA);
      if (role.equals("tool")) allowed.add("delivery");
      fields(root.path("metadata"), allowed);
      root.path("metadata").properties().forEach(entry -> {
        if (entry.getKey().equals("delivery")) return;
        if (!entry.getValue().isTextual()) throw invalid();
        metadata.put(entry.getKey(), entry.getValue().textValue());
      });
      if (role.equals("tool")) {
        var raw = root.path("metadata").get("delivery");
        if (raw != null) {
          fields(raw, Set.of("policies", "pages", "receipts", "fileReadBudgets"));
          metadata.putAll(JSON.treeToValue(raw, ToolResultMetadata.class).restore());
        }
        return ToolResponseMessage.builder().responses(results).metadata(metadata).build();
      }
      return AssistantMessage.builder().parts(parts).properties(metadata).build();
    } catch (java.io.IOException | IllegalArgumentException failure) { throw invalid(); }
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
      node.put("type", "reasoning").put("text", reasoning.text()).put("summary", reasoning.summary());
    else if (part instanceof ToolCallPart call) {
      node.put("type", "tool_call");
      var value = call.toolCall();
      node.putObject("call").put("id", value.id()).put("type", value.type())
          .put("name", value.name()).put("arguments", value.arguments());
    } else throw invalid();
    node.set("attributes", JSON.valueToTree(part.attributes()));
    OpaquePayload payload = part instanceof TextPart text ? text.payload()
        : part instanceof ReasoningPart reasoning ? reasoning.payload() : ((ToolCallPart) part).payload();
    if (payload != null) node.putObject("payload").put("provider", payload.provider())
        .put("kind", payload.kind()).put("data", payload.data());
    decodePart(node);
    return node;
  }

  private static MessagePart decodePart(JsonNode node) {
    String type = required(node, "type");
    fields(node, switch (type) {
      case "text" -> Set.of("type", "text", "payload", "attributes");
      case "reasoning" -> Set.of("type", "text", "summary", "payload", "attributes");
      case "tool_call" -> Set.of("type", "call", "payload", "attributes");
      default -> throw invalid();
    });
    var attributes = new HashMap<String, String>();
    var rawAttributes = node.path("attributes");
    if (!rawAttributes.isObject() || rawAttributes.size() > 64) throw invalid();
    rawAttributes.properties().forEach(entry -> {
      if (!entry.getValue().isTextual() || entry.getKey().length() > 128
          || entry.getKey().equals(StreamingParts.PART_INDEX_ATTRIBUTE)
          || entry.getKey().equals(StreamingParts.PARTIAL_ATTRIBUTE)) throw invalid();
      attributes.put(entry.getKey(), entry.getValue().textValue());
    });
    var raw = node.get("payload");
    if (raw != null && !raw.isNull()) fields(raw, Set.of("provider", "kind", "data"));
    var payload = raw == null || raw.isNull() ? null
        : new OpaquePayload(required(raw, "provider"), required(raw, "kind"), required(raw, "data"));
    return switch (type) {
      case "text" -> new TextPart(required(node, "text"), payload, attributes);
      case "reasoning" -> new ReasoningPart(text(node, "text"), text(node, "summary"), payload, attributes);
      case "tool_call" -> {
        var call = node.path("call");
        fields(call, Set.of("id", "type", "name", "arguments"));
        yield new ToolCallPart(new AssistantMessage.ToolCall(required(call, "id"), required(call, "type"),
            required(call, "name"), required(call, "arguments")), payload, attributes);
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
    if (node.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid model message schema");
  }
}
