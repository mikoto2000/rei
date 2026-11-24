package dev.mikoto2000.rei.modelmessage;

import static org.assertj.core.api.Assertions.assertThat;

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
