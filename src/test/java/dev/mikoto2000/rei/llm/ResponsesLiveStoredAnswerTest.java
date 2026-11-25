package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;

@Tag("integration")
class ResponsesLiveStoredAnswerTest {
  @TempDir Path root;

  @Test
  void inspectionReleasesItsReadCursorBeforeTheStoreValidatesThePayload() throws Exception {
    var config = new org.sqlite.SQLiteConfig();
    config.setBusyTimeout(50);
    var source = new org.sqlite.SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + root.resolve("payload.db"));
    var store = new ModelMessagePayloadStore(source);
    var scope = new ModelMessagePayloadStore.Scope("project", root.toString(), "session");
    var message =
        AssistantMessage.builder()
            .parts(
                List.of(
                    new ReasoningPart(
                        null,
                        null,
                        new OpaquePayload("openai", "reasoning", "fixture"),
                        Map.of("id", "reasoning-fixture")),
                    new TextPart("7417", null, Map.of())))
            .build();
    var ref = store.save(scope, "run", "final", message);
    assertThatCode(
            () -> {
              var selected = ResponsesLiveAcceptance.storedAnswer(store, source, scope, "run");
              assertThat(selected.sha256()).isEqualTo(ref.sha256());
              assertThat(selected.message().getParts()).hasSize(2);
            })
        .doesNotThrowAnyException();
  }
}
