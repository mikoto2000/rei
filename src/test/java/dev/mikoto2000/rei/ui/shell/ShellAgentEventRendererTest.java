package dev.mikoto2000.rei.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ErrorInformation;

class ShellAgentEventRendererTest {
  private final AgentEventFactory events = new AgentEventFactory(
      Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void rendersRunLifecycleAndFailureSummary() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.runStarted("run-1", "user", null));
    renderer.onEvent(events.runCompleted("run-1", 1_234, 456L));
    renderer.onEvent(events.runFailed("run-2", new ErrorInformation("IO", "permission denied", "stack")));
    assertEquals("[agent] running\n[agent] completed (1.2 s, 456 tokens)\n[agent] failed: permission denied\n", output.text());
  }

  @Test
  void reportsUnavailableTokensWhenProviderDoesNotReturnUsage() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.runCompleted("run-1", 20, null));
    assertEquals("[agent] completed (0.0 s, tokens unavailable)\n", output.text());
  }

  @Test
  void streamsJapaneseMessageWithoutRepeatingPrefixes() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.messageStarted("m1", "assistant"));
    renderer.onEvent(events.messageDelta("m1", "既存実装を"));
    renderer.onEvent(events.messageDelta("m1", "確認します。"));
    renderer.onEvent(events.messageCompleted("m1", "assistant", "既存実装を確認します。"));
    assertEquals("既存実装を確認します。\n", output.text());
  }

  @Test
  void putsInterleavedToolsOnSeparateLinesAndResumesAssistant() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.messageStarted("m1", "assistant"));
    renderer.onEvent(events.messageDelta("m1", "確認します。"));
    renderer.onEvent(events.toolStarted("c1", "readMultiFile", "files=8"));
    renderer.onEvent(events.toolCompleted("c1", "readMultiFile", 121, "ok"));
    renderer.onEvent(events.messageDelta("m1", "続けます。"));
    renderer.onEvent(events.messageCompleted("m1", "assistant", "確認します。続けます。"));
    assertEquals("確認します。\n  → readMultiFile\n  ✓ readMultiFile (121 ms)\n\n続けます。\n", output.text());
  }

  @Test
  void rendersToolFailureWithoutInventingMissingDuration() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.toolFailed("c1", "writeFile", new ErrorInformation("IO", "denied", null)));
    assertEquals("  ✗ writeFile: denied\n", output.text());
  }

  private static final class RecordingOutput implements ShellEventOutput {
    private final StringBuilder value = new StringBuilder();
    public void print(String text) { value.append(text); }
    public void println(String text) { value.append(text).append('\n'); }
    public void flush() { }
    String text() { return value.toString(); }
  }
}
