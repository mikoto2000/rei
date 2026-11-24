package dev.mikoto2000.rei.modelmessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.sqlite.SQLiteDataSource;

class ModelMessagePayloadStoreTest {
  @TempDir Path temporary;
  private final ModelMessagePayloadStore.Scope owner = new ModelMessagePayloadStore.Scope("project", "root", "conversation");

  SQLiteDataSource source() {
    var config = new org.sqlite.SQLiteConfig();
    config.setBusyTimeout(5000);
    var source = new SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + temporary.resolve("payloads.db"));
    return source;
  }

  AssistantMessage message() {
    return AssistantMessage.builder().parts(List.of(
        new ReasoningPart(null, "fixture summary", new OpaquePayload("openai", "reasoning", "opaque fixture bytes"),
            Map.of("id", "rs_fixture")),
        new TextPart("fixture commentary", null, Map.of("channel", "commentary")),
        new ToolCallPart(new AssistantMessage.ToolCall("call_fixture", "function", "fixture", "{}"),
            null, Map.of("id", "fc_fixture"))))
        .properties(Map.of("rei.llm.replay-origin-owner", "owner", "rei.llm.replay-origin-endpoint", "fingerprint"))
        .build();
  }

  @Test
  void aNewStoreInstanceRestoresEveryPartWithoutChangingOpaqueBytes() {
    var codec = new ModelMessageCodec();
    var input = message();
    var first = new ModelMessagePayloadStore(source());
    var ref = first.save(owner, "run", "iteration-1", input);
    var restarted = new ModelMessagePayloadStore(source());
    assertThat(codec.encode(restarted.load(owner, ref))).containsExactly(codec.encode(input));
    assertThat(ref.sha256()).matches("[0-9a-f]{64}");
  }

  @Test
  void retryIsIdempotentButCannotReplaceAnExistingMessage() {
    var store = new ModelMessagePayloadStore(source());
    var ref = store.save(owner, "run", "iteration-1", message());
    assertThat(store.save(owner, "run", "iteration-1", message())).isEqualTo(ref);
    assertThatThrownBy(() -> store.save(owner, "run", "iteration-1", new AssistantMessage("different")))
        .isInstanceOf(IllegalStateException.class).hasMessage("Model message persistence rejected").hasNoCause();
    assertThat(new ModelMessageCodec().encode(store.load(owner, ref)))
        .containsExactly(new ModelMessageCodec().encode(message()));
  }

  @Test
  void missingWrongOwnerAndTamperedHashAreRejected() {
    var store = new ModelMessagePayloadStore(source());
    var ref = store.save(owner, "run", "iteration-1", message());
    var foreign = new ModelMessagePayloadStore.Scope("other", "root", "conversation");
    assertThatThrownBy(() -> store.load(foreign, ref)).hasMessage("Model message persistence rejected");
    assertThatThrownBy(() -> store.load(owner, new ModelMessagePayloadStore.Ref(ref.id(), "0".repeat(64))))
        .hasMessage("Model message persistence rejected");
    assertThatThrownBy(() -> store.load(owner, new ModelMessagePayloadStore.Ref("missing", ref.sha256())))
        .hasMessage("Model message persistence rejected");
  }
}
