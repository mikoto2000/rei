package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Preparing an approval input performs no model call, credential lookup, or tool execution. */
class InferenceExportRequestTest {
  @Test
  void historicalNotificationCanBeFrozenForInference() {
    var history =
        dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.historical(
            org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content("historical notification")
                .properties(java.util.Map.of("source", "BEHAVIOR_NOTIFICATION", "sourceId", "notice"))
                .build(),
            1);
    var prompt =
        new Prompt(
            List.of(new SystemMessage("system"), history, new UserMessage("question")),
            ToolCallingChatOptions.builder().model("fixture-model").build());
    var prepared = InferenceExportRequest.prepare(prompt);
    var restored = prepared.prompt().getInstructions().get(1);
    assertThat(restored.getText()).isEqualTo(history.getText());
    assertThat(restored.getMetadata()).containsAllEntriesOf(history.getMetadata());
    assertThat(InferenceExportRequest.prepare(prepared.prompt()).fingerprint())
        .isEqualTo(prepared.fingerprint());
    var changed =
        new Prompt(
            List.of(
                new SystemMessage("system"),
                new org.springframework.ai.chat.messages.AssistantMessage("changed notification"),
                new UserMessage("question")),
            prompt.getOptions());
    assertThat(InferenceExportRequest.prepare(changed).fingerprint())
        .isNotEqualTo(prepared.fingerprint());
  }

  @Test
  void ordinaryInputIsFrozenAndItsFingerprintIsStable() {
    var prompt = prompt("fixture question", "fixture system");
    var prepared = new AtomicReference<InferenceExportRequest>();
    assertThatCode(() -> prepared.set(InferenceExportRequest.prepare(prompt)))
        .doesNotThrowAnyException();
    assertThat(prepared.get().prompt()).isNotSameAs(prompt);
    assertThat(prepared.get().fingerprint()).matches("[0-9a-f]{64}");
    assertThat(InferenceExportRequest.prepare(prompt).fingerprint())
        .isEqualTo(prepared.get().fingerprint());
    assertThat(prepared.get().toString()).doesNotContain("fixture question", "fixture system");
  }

  @Test
  void changedUserHistoryAndSystemInstructionsCannotReuseTheSameContentBinding() {
    var original = new AtomicReference<InferenceExportRequest>();
    assertThatCode(() -> original.set(InferenceExportRequest.prepare(prompt("one", "system"))))
        .doesNotThrowAnyException();
    assertThat(InferenceExportRequest.prepare(prompt("two", "system")).fingerprint())
        .isNotEqualTo(original.get().fingerprint());
    assertThat(InferenceExportRequest.prepare(prompt("one", "changed system")).fingerprint())
        .isNotEqualTo(original.get().fingerprint());
    var history =
        new Prompt(
            List.of(
                new SystemMessage("system"), new UserMessage("history"), new UserMessage("one")),
            ToolCallingChatOptions.builder().model("fixture-model").build());
    assertThat(InferenceExportRequest.prepare(history).fingerprint())
        .isNotEqualTo(original.get().fingerprint());
  }

  @Test
  void mutableToolDefinitionsAreCapturedWithoutChangingCallbackAuthority() {
    var definition = new AtomicReference<>(definition("original description"));
    var executions = new java.util.concurrent.atomic.AtomicInteger();
    var callback =
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return definition.get();
          }

          public String call(String input) {
            executions.incrementAndGet();
            return "fixture result";
          }
        };
    var prompt =
        new Prompt(
            "fixture",
            ToolCallingChatOptions.builder()
                .model("fixture-model")
                .toolCallbacks(List.of(callback))
                .build());
    var prepared = new AtomicReference<InferenceExportRequest>();
    assertThatCode(() -> prepared.set(InferenceExportRequest.prepare(prompt)))
        .doesNotThrowAnyException();
    String before = prepared.get().fingerprint();
    definition.set(definition("changed description"));
    var frozen = (ToolCallingChatOptions) prepared.get().prompt().getOptions();
    assertThat(frozen.getToolCallbacks().getFirst().getToolDefinition().description())
        .isEqualTo("original description");
    assertThat(prepared.get().fingerprint()).isEqualTo(before);
    assertThat(InferenceExportRequest.prepare(prompt).fingerprint()).isNotEqualTo(before);
    assertThat(executions).hasValue(0);
    assertThat(frozen.getToolCallbacks().getFirst().call("{}")).isEqualTo("fixture result");
    assertThat(executions).hasValue(1);
  }

  @Test
  void imageBytesCannotChangeBetweenApprovalAndSend() {
    byte[] bytes = {1, 2, 3, 4};
    var media =
        new org.springframework.ai.content.Media(
            org.springframework.util.MimeTypeUtils.IMAGE_PNG,
            new org.springframework.core.io.ByteArrayResource(bytes));
    var prompt =
        new Prompt(
            List.of(UserMessage.builder().text("fixture image").media(List.of(media)).build()),
            ToolCallingChatOptions.builder().model("fixture-model").build());
    var prepared = new AtomicReference<InferenceExportRequest>();
    assertThatCode(() -> prepared.set(InferenceExportRequest.prepare(prompt)))
        .doesNotThrowAnyException();
    // Media copies a Resource on construction; mutate the actual supplied message data.
    ((byte[]) media.getData())[0] = 9;
    assertThat(media.getDataAsByteArray()[0]).isEqualTo((byte) 9);
    var frozen = (UserMessage) prepared.get().prompt().getInstructions().getFirst();
    assertThat(frozen.getMedia().getFirst().getDataAsByteArray())
        .isEqualTo(new byte[] {1, 2, 3, 4});
    assertThat(InferenceExportRequest.prepare(prompt).fingerprint())
        .isNotEqualTo(prepared.get().fingerprint());
  }

  private static Prompt prompt(String user, String system) {
    return new Prompt(
        List.of(new SystemMessage(system), new UserMessage(user)),
        ToolCallingChatOptions.builder().model("fixture-model").build());
  }

  private static ToolDefinition definition(String description) {
    return ToolDefinition.builder()
        .name("fixtureTool")
        .description(description)
        .inputSchema("{\"type\":\"object\"}")
        .build();
  }
}
