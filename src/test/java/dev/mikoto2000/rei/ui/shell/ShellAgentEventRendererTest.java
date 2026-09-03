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
import dev.mikoto2000.rei.topic.IdleTriggerRejectReason;
import dev.mikoto2000.rei.topic.TopicGenerationStage;
import dev.mikoto2000.rei.topic.TopicRejectionReason;
import dev.mikoto2000.rei.topic.TopicScoreBreakdown;
import dev.mikoto2000.rei.topic.TopicSpeakSkipReason;

class ShellAgentEventRendererTest {
  private final AgentEventFactory events = new AgentEventFactory(
      Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void rendersRunLifecycleAndFailureSummary() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.runStarted("run-1", "user", null));
    renderer.onEvent(events.runCompleted("run-1", 1_234, 456L, 123.45d, 78.94d, 65.43d));
    renderer.onEvent(events.runFailed("run-2", new ErrorInformation("IO", "permission denied", "stack")));
    assertEquals("[agent] running\n[agent] completed (1.2 s, 456 tokens, TTFT 123.5 ms, output 78.9 tok/s, end-to-end 65.4 tok/s)\n[agent] failed: permission denied\n", output.text());
  }

  @Test
  void reportsUnavailableTokensWhenProviderDoesNotReturnUsage() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.runCompleted("run-1", 20, null));
    assertEquals("[agent] completed (0.0 s, tokens unavailable, TTFT unavailable, output speed unavailable, end-to-end speed unavailable)\n", output.text());
  }

  @Test
  void rendersLlmRequestAndResponseLifecycle() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.llmRequestStarted("run-1", "request-1", "chat"));
    renderer.onEvent(events.llmResponseCompleted("run-1", "request-1", 1_234));
    assertEquals("[llm] request sent (chat)\n[llm] response received (1234 ms)\n", output.text());
  }

  @Test
  void streamsJapaneseMessageWithoutRepeatingPrefixes() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.messageStarted("m1", "assistant"));
    renderer.onEvent(events.messageDelta("m1", "既存実装を"));
    renderer.onEvent(events.messageDelta("m1", "確認します。"));
    renderer.onEvent(events.messageCompleted("m1", "assistant", "既存実装を確認します。"));
    assertEquals("=== answer ===\n既存実装を確認します。\n", output.text());
  }

  @Test
  void streamsThinkingWithAnExplicitHeadingBeforeTheAnswer() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    renderer.onEvent(events.thinkingStarted("t1"));
    renderer.onEvent(events.thinkingDelta("t1", "状況を"));
    renderer.onEvent(events.thinkingDelta("t1", "確認します。"));
    renderer.onEvent(events.thinkingCompleted("t1", "状況を確認します。"));
    renderer.onEvent(events.messageStarted("m1", "assistant"));
    renderer.onEvent(events.messageDelta("m1", "回答です。"));
    renderer.onEvent(events.messageCompleted("m1", "assistant", "回答です。"));

    assertEquals("=== thinking ===\n状況を確認します。\n=== answer ===\n回答です。\n", output.text());
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
    assertEquals("=== answer ===\n確認します。\n  → readMultiFile files=8\n  ✓ readMultiFile (121 ms)\n\n続けます。\n", output.text());
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

  @Test
  void rendersTopicEventsAndUnknownEventsDoNotBreakRenderer() {
    RecordingOutput output = new RecordingOutput();
    ShellAgentEventRenderer renderer = new ShellAgentEventRenderer(output);
    TopicScoreBreakdown score = new TopicScoreBreakdown(0.16, 0.17, 0.27, 0.18, 0.05, 0.01, 0.82);
    renderer.onEvent(events.topicGenerationStarted("run-1", "tg-1", "agent-run"));
    renderer.onEvent(events.topicIdleTriggerEvaluated(IdleTriggerRejectReason.INSUFFICIENT_IDLE, false,
        java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(2)));
    renderer.onEvent(events.topicCandidatesRefreshed(2));
    renderer.onEvent(events.topicCandidateGenerated("run-1", "tg-1", "topic-1", "UNFINISHED_WORK",
        "WORKING_SET", "Working Set 測定", "効果測定が未実施", 1.0, 1.0, 1.0, 0.0, 1.0));
    renderer.onEvent(events.topicCandidateScored("run-1", "tg-1", "topic-1", score));
    renderer.onEvent(events.topicCandidateRejected("run-1", "tg-1", "topic-2", TopicRejectionReason.LOW_SCORE, 0.43));
    renderer.onEvent(events.topicSelected("run-1", "tg-1", "topic-1", 0.82, 1));
    renderer.onEvent(events.topicSpeakSkipped("run-1", "tg-1", "topic-1", TopicSpeakSkipReason.COOLDOWN,
        Instant.parse("2026-08-23T00:30:00Z")));
    renderer.onEvent(events.topicAutoSpeakSuppressed("stale activity"));
    renderer.onEvent(events.topicSpoken("run-1", "tg-1", "topic-1", "m1",
        Instant.parse("2026-08-23T00:00:00Z"), "話題です"));
    renderer.onEvent(events.topicGenerationCompleted("run-1", "tg-1", 2, 2, 1, "topic-1", true, 184));
    renderer.onEvent(events.topicGenerationFailed("run-1", "tg-2", TopicGenerationStage.RANKING,
        new IllegalStateException("ranking failed")));

    assertEquals("[topic] generation started\n"
        + "        id: tg-1\n"
        + "[topic] idle trigger skipped\n"
        + "        idle: 30.00s\n"
        + "        required: 120.00s\n"
        + "        reason: INSUFFICIENT_IDLE\n"
        + "[topic] candidates refreshed\n"
        + "        candidates: 2\n"
        + "[topic] candidate\n"
        + "        id: topic-1\n"
        + "        type: unfinished_work\n"
        + "        source: working_set\n"
        + "        topic: Working Set 測定\n"
        + "        reason: 効果測定が未実施\n"
        + "[topic] scored\n"
        + "        id: topic-1\n"
        + "        score: 0.82\n"
        + "        priority: +0.16\n"
        + "        freshness: +0.17\n"
        + "        usefulness: +0.27\n"
        + "        confidence: +0.18\n"
        + "        intrusiveness: -0.05\n"
        + "        repetition: -0.01\n"
        + "[topic] rejected\n"
        + "        id: topic-2\n"
        + "        reason: LOW_SCORE\n"
        + "        score: 0.43\n"
        + "[topic] selected\n"
        + "        id: topic-1\n"
        + "        score: 0.82\n"
        + "        rank: 1\n"
        + "[topic] speak skipped\n"
        + "        id: topic-1\n"
        + "        reason: COOLDOWN\n"
        + "        nextAllowedAt: 2026-08-23T00:30:00Z\n"
        + "[topic] auto speak suppressed\n"
        + "        reason: stale activity\n"
        + "[topic] spoken\n"
        + "        id: topic-1\n"
        + "        message: m1\n"
        + "[topic] generation completed\n"
        + "        candidates: 2\n"
        + "        scored: 2\n"
        + "        rejected: 1\n"
        + "        selected: topic-1\n"
        + "        spoken: true\n"
        + "        duration: 184ms\n"
        + "[topic] generation failed\n"
        + "        stage: RANKING\n"
        + "        error: IllegalStateException: ranking failed\n", output.text());
  }

  private static final class RecordingOutput implements ShellEventOutput {
    private final StringBuilder value = new StringBuilder();
    public void print(String text) { value.append(text); }
    public void println(String text) { value.append(text).append('\n'); }
    public void flush() { }
    String text() { return value.toString(); }
  }
}
