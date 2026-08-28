package dev.mikoto2000.rei.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.SkillCandidatesEvaluatedPayload;

class ShellAgentEventRendererTest {
  private final AgentEventFactory events = new AgentEventFactory(
      Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void rendersRunLifecycleAndFailureSummary() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.runStarted("run-1", "user", null));
    renderer.onEvent(events.runCompleted("run-1", 1_234, 456L, 78.94d));
    renderer.onEvent(events.runFailed("run-2", new ErrorInformation("IO", "permission denied", "stack")));
    assertEquals("[agent] running\n[agent] completed (1.2 s, 456 tokens, 78.9 tok/s)\n[agent] failed: permission denied\n", output.text());
  }

  @Test
  void reportsUnavailableTokensWhenProviderDoesNotReturnUsage() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.runCompleted("run-1", 20, null));
    assertEquals("[agent] completed (0.0 s, tokens unavailable, speed unavailable)\n", output.text());
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
    assertEquals("確認します。\n  → readMultiFile files=8\n  ✓ readMultiFile (121 ms)\n\n続けます。\n", output.text());
  }

  @Test
  void rendersToolArgumentsAsOneSafeLineAndOmitsEmptyArguments() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.toolStarted("c1", "writeFile", "{\n  \"path\": \"memo.txt\"\u001b\n}"));
    renderer.onEvent(events.toolStarted("c2", "listFiles", ""));
    assertEquals("  → writeFile { \"path\": \"memo.txt\" }\n  → listFiles\n", output.text());
  }

  @Test
  void rendersToolFailureWithoutInventingMissingDuration() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.toolFailed("c1", "writeFile", new ErrorInformation("IO", "denied", null)));
    assertEquals("  ✗ writeFile: denied\n", output.text());
  }

  @Test
  void rendersSkillSelectionLifecycleAndWarnings() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.skillSelectionStarted("selection-1"));
    renderer.onEvent(events.skillSelectionCompleted("selection-1", java.util.List.of("explicit-skill"),
        java.util.List.of("implicit-skill"), java.util.List.of("[warn] missing skill")));
    renderer.onEvent(events.skillSelectionFailed("selection-2",
        new ErrorInformation("IllegalStateException", "selection unavailable", null)));
    assertEquals("[skill] selecting\n[warn] missing skill\n"
        + "[skill] selected: explicit-skill (explicit), implicit-skill (implicit)\n"
        + "[skill] selection failed: selection unavailable\n", output.text());
  }

  @Test
  void rendersSkillRoutingMetricsSelectionAndNoSelection() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.skillRoutingStarted("run-1", "routing-1", 27, 2));
    renderer.onEvent(events.skillRoutingCompleted("run-1", "routing-1", 1_834, 27, "rspress", 2,
        1_560L, null, null, java.util.List.of(), java.util.List.of("rspress"), java.util.List.of()));
    renderer.onEvent(events.skillRoutingCompleted("run-1", "routing-2", 420, 27, null, 3,
        390L, null, null, java.util.List.of(), java.util.List.of(), java.util.List.of()));

    assertEquals("[skill] selecting from 27 skills... (#2)\n"
        + "[skill] rspress selected from 27 skills (1.83s, selector 1.56s)\n"
        + "[skill] no skill selected from 27 skills (420ms, selector 390ms)\n", output.text());
  }

  @Test
  void rendersSkillRoutingFailureAsBoundedSummary() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.skillRoutingFailed("run-1", "routing-1", 742, 27, 1,
        new ErrorInformation("IllegalStateException", "selection unavailable\nsecret", null)));
    assertEquals("[skill] selection failed after 742ms: selection unavailable secret\n", output.text());
  }

  @Test
  void rendersSkillCandidateShadowEvaluationCompactly() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.skillCandidatesEvaluated("run-1", "routing-1", 42, 4, "rspress", true,
        false, true, true, java.util.List.of(
            new SkillCandidatesEvaluatedPayload.CandidateScore("typescript", 7),
            new SkillCandidatesEvaluatedPayload.CandidateScore("rspress", 6))));

    assertEquals("[skill-candidate] 42 -> 2 skills (4ms), actual=rspress, top5=hit\n", output.text());
  }

  @Test
  void rendersWorkingSetItemChangesWithExistingReasons() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.workingSetItemAdded("item-1", "file", "Foo.java", "/project/src/Foo.java",
        "search selection"));
    renderer.onEvent(events.workingSetItemRemoved("/project/src/Old.java", "capacity eviction"));
    assertEquals("[working-set] + Foo.java (search selection)\n"
        + "[working-set] - Old.java (capacity eviction)\n", output.text());
  }

  @Test
  void rendersWorkingSetItemsWithoutInventingAReasonAndTruncatesLongReasons() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.workingSetItemAdded("item-1", "file", "Foo.java", "Foo.java", null));
    renderer.onEvent(events.workingSetItemRemoved("Old.java", "x".repeat(200) + "\nsecret"));
    String[] lines = output.text().split("\n");
    assertEquals("[working-set] + Foo.java", lines[0]);
    assertTrue(lines[1].startsWith("[working-set] - Old.java ("));
    assertTrue(lines[1].endsWith("…)"));
    assertTrue(lines[1].length() <= 170);
  }

  @Test
  void rendersWorkingSetSearchLifecycleAsCompactSummaries() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.workingSetSearchStarted("search-1", "ToolCallbackProvider\nsecret", "searchAndRead", 1));
    renderer.onEvent(events.workingSetSearchCompleted("search-1", 91, 18, 5, 2, 1, 1, 2));
    assertEquals("[working-set] → search \"ToolCallbackProvider secret\"\n"
        + "[working-set] ✓ 18 hits → 5 candidates → 2 selected, 1 already present (91 ms)\n",
        output.text());
  }

  private static final class RecordingOutput implements ShellEventOutput {
    private final StringBuilder value = new StringBuilder();
    public void print(String text) { value.append(text); }
    public void println(String text) { value.append(text).append('\n'); }
    public void flush() { }
    String text() { return value.toString(); }
  }
}
