package dev.mikoto2000.rei.modelmessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;

/** Versioned model-message serialization, independent of transport, storage and execution. */
public final class ModelMessageCodec {
  private static final ObjectMapper JSON = new ObjectMapper();

  public byte[] encode(Message message) {
    if (!(message instanceof AssistantMessage assistant)) throw invalid();
    var root = JSON.createObjectNode().put("schemaVersion", 1).put("role", "assistant");
    var parts = root.putArray("parts");
    assistant.getParts().forEach(part -> parts.add(encodePart(part)));
    var metadata = root.putObject("metadata");
    for (var key : java.util.List.of("rei.llm.replay-origin-owner", "rei.llm.replay-origin-endpoint")) {
      var value = assistant.getMetadata().get(key);
      if (value instanceof String text) metadata.put(key, text);
    }
    try { return JSON.writeValueAsBytes(root); }
    catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw invalid(); }
  }

  public Message decode(byte[] bytes) {
    try {
      var root = JSON.readTree(bytes);
      if (root == null || root.path("schemaVersion").asInt() != 1
          || !"assistant".equals(root.path("role").asText())) throw invalid();
      var parts = new java.util.ArrayList<MessagePart>();
      for (var part : root.path("parts")) parts.add(decodePart(part));
      var metadata = new HashMap<String, Object>();
      root.path("metadata").properties().forEach(entry -> metadata.put(entry.getKey(), entry.getValue().asText()));
      return AssistantMessage.builder().parts(parts).properties(metadata).build();
    } catch (java.io.IOException failure) { throw invalid(); }
  }

  private static ObjectNode encodePart(MessagePart part) {
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
    return node;
  }

  private static MessagePart decodePart(JsonNode node) {
    var attributes = new HashMap<String, String>();
    node.path("attributes").properties().forEach(entry -> attributes.put(entry.getKey(), entry.getValue().asText()));
    var raw = node.get("payload");
    var payload = raw == null || raw.isNull() ? null
        : new OpaquePayload(raw.path("provider").asText(), raw.path("kind").asText(), raw.path("data").asText());
    return switch (node.path("type").asText()) {
      case "text" -> new TextPart(text(node, "text"), payload, attributes);
      case "reasoning" -> new ReasoningPart(text(node, "text"), text(node, "summary"), payload, attributes);
      case "tool_call" -> {
        var call = node.path("call");
        yield new ToolCallPart(new AssistantMessage.ToolCall(call.path("id").asText(), call.path("type").asText(),
            call.path("name").asText(), call.path("arguments").asText()), payload, attributes);
      }
      default -> throw invalid();
    };
  }

  private static String text(JsonNode node, String field) {
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid model message schema");
  }
}
