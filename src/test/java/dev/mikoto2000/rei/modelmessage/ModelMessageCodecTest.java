package dev.mikoto2000.rei.modelmessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;

class ModelMessageCodecTest {
  @Test
  void rawStringPoliciesCannotBecomeTrustedDeliveryMetadata() {
    var message = org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("call_one", "fixture", "fixture")))
        .metadata(Map.of("rei.deliveryPolicies", Map.of("call_one", "FILE_CONTENT"))).build();
    assertThatThrownBy(() -> new ModelMessageCodec().encode(message))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid model message schema").hasNoCause();
  }

  @Test
  void duplicateToolOutputsCannotBePublished() {
    var result = new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("call_one", "fixture", "fixture");
    assertThatThrownBy(() -> new ModelMessageCodec().encode(
        org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(result, result)).build()))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid model message schema").hasNoCause();
  }

  static java.util.stream.Stream<String> invalidDelivery() {
    return java.util.stream.Stream.of(
        "{\"policies\":{\"foreign_call\":\"FILE_CONTENT\"}}",
        "{\"policies\":{\"call_one\":42}}",
        "{\"pages\":{\"call_one\":{\"ref\":\"fixture\",\"offset\":-1}}}",
        "{\"receipts\":{\"call_one\":{\"serializedHash\":\"invalid\"}}}",
        "{\"receipts\":{\"call_one\":{\"serializedHash\":\"" + "a".repeat(64) + "\",\"@class\":\"private-fixture\"}}}",
        "{\"fileReadBudgets\":{\"call_one\":{\"tokens\":-1}}}",
        "{\"fileReadBudgets\":{\"call_one\":{\"tokens\":1.5}}}");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("invalidDelivery")
  void malformedOrDanglingDeliveryEvidenceFailsClosed(String delivery) {
    String document = "{\"schemaVersion\":1,\"role\":\"tool\",\"parts\":[{\"type\":\"tool_result\",\"callId\":\"call_one\",\"name\":\"fixture\",\"data\":\"fixture\"}],\"metadata\":{\"delivery\":" + delivery + "}}";
    assertThatThrownBy(() -> new ModelMessageCodec().decode(document.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid model message schema").hasNoCause();
  }
  @Test
  void toolResultsKeepCallReferencesModelProjectionAndTypedDeliveryMetadata() {
    var delivery = new dev.mikoto2000.rei.tooldelivery.ToolResultMetadata(
        Map.of("call_one", "FILE_CONTENT", "call_two", "GENERAL"),
        Map.of("call_one", new dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.Page("raw-fixture", 48)),
        Map.of("call_one", new dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.Receipt("a".repeat(64))),
        Map.of("call_one", new dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.FileReadBudget(8000)));
    var original = org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("call_one", "fixture_read", "actual model projection\n日本語"),
        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("call_two", "fixture_tool", "{\"ok\":true}")))
        .metadata(delivery.restore()).build();
    var codec = new ModelMessageCodec();
    byte[] bytes = codec.encode(original);
    var restored = (org.springframework.ai.chat.messages.ToolResponseMessage) codec.decode(bytes);
    assertThat(restored.getResponses()).containsExactlyElementsOf(original.getResponses());
    assertThat(dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.capture(restored)).isEqualTo(delivery);
    assertThat(((Map<?, ?>) restored.getMetadata().get("rei.deliveryPolicies")).get("call_one"))
        .isInstanceOf(dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.PolicyValue.class);
    assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("dev.mikoto2000", "@class", "org.springframework");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void encoderCannotPersistPartsThatItsDecoderWouldReject(boolean missingCallId) {
    var attributes = new java.util.HashMap<String, String>();
    for (int i = 0; i < 65; i++) attributes.put("fixture" + i, "value");
    org.springframework.ai.chat.messages.part.MessagePart part = missingCallId
        ? ToolCallPart.of(new AssistantMessage.ToolCall(null, "function", "fixture_tool", "{}"))
        : new TextPart("fixture", null, attributes);
    assertThatThrownBy(() -> new ModelMessageCodec().encode(AssistantMessage.builder().part(part).build()))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid model message schema").hasNoCause();
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void rawIndexedStreamPartsCannotBePersistedEvenWhenOneItemIsComplete(boolean partial) {
    var text = TextPart.of("fixture");
    var part = partial ? org.springframework.ai.chat.messages.part.StreamingParts.partial(text, 0)
        : org.springframework.ai.chat.messages.part.StreamingParts.complete(text, 0);
    assertThatThrownBy(() -> new ModelMessageCodec().encode(AssistantMessage.builder().part(part).build()))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid model message schema").hasNoCause();
  }

  @Test
  void serializedBytesAreBoundedWithoutTruncatingPayloads() {
    var message = AssistantMessage.builder().part(new ReasoningPart(null, null,
        new OpaquePayload("fixture", "encrypted_content", "x".repeat(8 * 1024 * 1024)), Map.of())).build();
    assertThatThrownBy(() -> new ModelMessageCodec().encode(message))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid model message schema").hasNoCause();
  }

  static java.util.stream.Stream<String> invalidDocuments() {
    String empty = "{\"schemaVersion\":1,\"role\":\"assistant\",\"parts\":[],\"metadata\":{}}";
    return java.util.stream.Stream.of(
        empty + "{}",
        empty.replace("\"schemaVersion\":1", "\"schemaVersion\":2,\"schemaVersion\":1"),
        empty.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
        empty.replace("\"schemaVersion\":1", "\"schemaVersion\":1.5"),
        empty.replace("\"parts\":[]", "\"parts\":{}"),
        empty.replace("\"metadata\":{}", "\"metadata\":{\"@class\":\"java.lang.Runtime\"}"),
        empty.replace("\"metadata\":{}", "\"metadata\":{},\"unknown\":\"private-fixture\""),
        empty.replace("\"parts\":[]", "\"parts\":[{\"type\":\"text\",\"text\":\"fixture\",\"attributes\":{\"itemId\":12}}]"),
        empty.replace("\"parts\":[]", "\"parts\":[{\"type\":\"reasoning\",\"payload\":{\"provider\":\"fixture\",\"kind\":\"encrypted_content\",\"data\":42},\"attributes\":{}}]"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("invalidDocuments")
  void malformedOrAmbiguousSchemasFailClosedWithoutEchoingData(String document) {
    assertThatThrownBy(() -> new ModelMessageCodec().decode(document.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid model message schema")
        .hasMessageNotContaining("private-fixture").hasNoCause();
  }

  @Test
  void orderedPartsOpaqueBytesPlaintextSummaryAndProtocolAttributesRoundTrip() {
    var original = AssistantMessage.builder().parts(List.of(
        new ReasoningPart(null, "fixture summary", new OpaquePayload("fixture-provider", "encrypted_content", "opaque-one+/="),
            Map.of("itemId", "rs_one", "itemStatus", "completed")),
        new ToolCallPart(new AssistantMessage.ToolCall("call_one", "function", "fixture_tool", "{\"query\":\"日本語\"}"), null,
            Map.of("itemId", "fc_one", "itemStatus", "completed")),
        new TextPart("working", null, Map.of("itemId", "msg_one", "itemStatus", "completed", "phase", "commentary")),
        new ReasoningPart("fixture plaintext", null, null, Map.of("itemId", "rs_two", "itemStatus", "completed")),
        new ReasoningPart(null, null, new OpaquePayload("fixture-provider", "encrypted_content", "opaque-two+/="),
            Map.of("itemId", "rs_three"))))
        .properties(Map.of("rei.llm.replay-origin-owner", "fixture-owner", "rei.llm.replay-origin-endpoint", "fixture-fingerprint"))
        .build();
    var codec = new ModelMessageCodec();
    byte[] encoded = codec.encode(original);
    var restored = (AssistantMessage) codec.decode(encoded);
    assertThat(restored.getParts()).containsExactlyElementsOf(original.getParts());
    assertThat(restored.getMetadata()).containsAllEntriesOf(original.getMetadata());
    assertThat(((ReasoningPart) restored.getParts().get(0)).payload().data().getBytes(StandardCharsets.UTF_8))
        .containsExactly("opaque-one+/=".getBytes(StandardCharsets.UTF_8));
    assertThat(((ToolCallPart) restored.getParts().get(1)).toolCall().id()).isEqualTo("call_one");
    assertThat(restored.getParts().get(1).attributes()).containsEntry("itemId", "fc_one");
    assertThat(restored.getParts().get(2).attributes()).containsEntry("phase", "commentary");
    assertThat(new String(encoded, StandardCharsets.UTF_8)).contains("\"schemaVersion\":1")
        .doesNotContain("org.springframework", "@class", "dev.mikoto2000");
  }
}
