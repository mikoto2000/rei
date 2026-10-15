package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunEventOutputTest {
  @Test
  void compressionDetailsAndCooldownDoNotSplitAnswerChunks() throws Exception {
    event("message.delta", "m", "続き");
    output.accept(
        "context.compression.failed",
        BackendClient.JSON.readTree(
            "{\"reason\":\"summary_timeout\",\"details\":\"timeoutSeconds=60\\ntoken=hidden\"}"));
    output.accept(
        "context.compression.skipped",
        BackendClient.JSON.readTree(
            "{\"reason\":\"retry_cooldown\",\"details\":\"retryAfterSeconds=120\"}"));
    assertTrue(lines.isEmpty());
    event("message.delta", "m", "を進めます。");
    event("message.completed", "m", "続きを進めます。");
    assertEquals(
        List.of(
            "続きを進めます。",
            "[context.compression] failed: summary_timeout (timeoutSeconds=60 token=[REDACTED])",
            "[context.compression] skipped: retry_cooldown (retryAfterSeconds=120)"),
        lines);
  }

  @Test
  void oldCompressionPayloadAndNullDetailsRemainReadable() throws Exception {
    output.accept(
        "context.compression.failed",
        BackendClient.JSON.readTree("{\"reason\":\"summary_empty\"}"));
    output.accept(
        "context.compression.skipped",
        BackendClient.JSON.readTree("{\"reason\":\"retry_cooldown\",\"details\":null}"));
    assertEquals(
        List.of(
            "[context.compression] failed: summary_empty",
            "[context.compression] skipped: retry_cooldown"),
        lines);
  }

  @Test
  void nullErrorCodeDoesNotDisplayNullAsAReason() throws Exception {
    output.accept(
        "agent.run.failed",
        BackendClient.JSON.readTree(
            "{\"error\":{\"message\":null,\"errorType\":\"IOException\",\"code\":null}}"));
    assertEquals(List.of("[agent.run.failed] 原因: IOException"), lines);
  }

  @Test
  void runCancellationDisplaysRedactedReasonAndCode() throws Exception {
    output.accept(
        "agent.run.cancelled",
        BackendClient.JSON.readTree(
            "{\"error\":{\"message\":\"approval denied token=hidden\",\"code\":\"approval_denied\"}}"));
    assertEquals(
        List.of("[agent.run.cancelled] 原因: [approval_denied] approval denied token=[REDACTED]"),
        lines);
  }

  @Test
  void blankToolFailureStillReportsExceptionType() throws Exception {
    output.accept(
        "tool.failed",
        BackendClient.JSON.readTree(
            "{\"toolName\":\"readFile\",\"error\":{\"message\":\"\",\"errorType\":\"AccessDeniedException\"}}"));
    assertEquals(List.of("✗ readFile: AccessDeniedException"), lines);
  }

  @Test
  void permissionFailureShowsItsSpecificReasonWithoutJsonEscapes() throws Exception {
    output.accept(
        "tool.failed",
        BackendClient.JSON.readTree(
            "{\"toolName\":\"delegateTask\",\"error\":{\"message\":\"PERMISSION_DENIED (ACCOUNT_REQUIRED: accountRef を指定してください。)\"}}"));
    assertEquals(
        List.of("✗ delegateTask: PERMISSION_DENIED (ACCOUNT_REQUIRED: accountRef を指定してください。)"),
        lines);
  }

  @Test
  void delegatedFailureDisplaysItsCauseAsReadableText() throws Exception {
    output.accept(
        "subagent.failed",
        BackendClient.JSON.readTree(
            "{\"agentId\":\"reviewer\",\"status\":\"FAILED\",\"subAgentRunId\":\"child\",\"failureReason\":\"SEND_LIMIT: 資料を分割してください。\"}"));
    assertEquals(
        List.of("[subagent] reviewer FAILED [child]", "[subagent] 原因: SEND_LIMIT: 資料を分割してください。"),
        lines);
  }

  private final List<String> lines = new ArrayList<>();
  private final List<String> headings = new ArrayList<>();
  private final RunEventOutput output =
      new RunEventOutput(
          line -> {
            if (line.startsWith("=== ")) headings.add(line);
            else lines.add(line);
          });
  private final java.time.Instant timestamp = java.time.Instant.parse("2026-10-12T11:34:56Z");

  private void event(String type, String id, String text) throws Exception {
    var payload = BackendClient.JSON.createObjectNode();
    payload.put(type.startsWith("thinking.") ? "thinkingId" : "messageId", id);
    payload.put(type.endsWith(".delta") ? "delta" : "text", text);
    output.accept(type, payload, timestamp);
  }

  @Test
  void headingsUseShellFormatAndEventTime() throws Exception {
    event("thinking.started", "t", "");
    event("thinking.delta", "t", "検討");
    event("thinking.completed", "t", "検討");
    event("message.started", "m", "");
    event("message.delta", "m", "回答");
    event("message.completed", "m", "回答");
    String time =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(timestamp);
    assertEquals(
        List.of("=== thinking (" + time + ") ===", "=== answer (" + time + ") ==="), headings);
    assertEquals(List.of("検討", "回答"), lines);
  }

  @Test
  void userMessagesDoNotCreateAnswerHeadings() throws Exception {
    output.accept(
        "message.started",
        BackendClient.JSON.readTree("{\"messageId\":\"u\",\"role\":\"user\"}"),
        timestamp);
    event("message.delta", "u", "入力");
    output.accept(
        "message.completed",
        BackendClient.JSON.readTree("{\"messageId\":\"u\",\"role\":\"user\",\"text\":\"入力\"}"),
        timestamp);
    assertTrue(headings.isEmpty());
    assertTrue(lines.isEmpty());
  }

  @Test
  void answerChunksJoinAndPreserveOnlyContentNewlines() throws Exception {
    event("message.started", "m", "");
    event("message.delta", "m", "いい");
    event("message.delta", "m", "質問\r");
    assertTrue(lines.isEmpty());
    event("message.delta", "m", "\nですね。\n最後");
    assertEquals(List.of("いい質問", "ですね。"), lines);
    event("message.completed", "m", "いい質問\r\nですね。\n最後");
    assertEquals(List.of("いい質問", "ですね。", "最後"), lines);
  }

  @Test
  void thinkingIsVisibleAndCompletedTextIsNotRepeated() throws Exception {
    event("thinking.started", "t", "");
    event("thinking.delta", "t", "考え");
    event("thinking.delta", "t", "中です");
    event("thinking.completed", "t", "考え中です");
    event("message.completed", "m", "回答です");
    assertEquals(List.of("考え中です", "回答です"), lines);
  }

  @Test
  void completedOnlyThinkingAndAnswerAreVisible() throws Exception {
    event("thinking.completed", "t", "検討結果");
    event("message.completed", "m", "回答\n続き");
    assertEquals(List.of("検討結果", "回答", "続き"), lines);
  }

  @Test
  void otherEventsWaitUntilAnswerCompletesAndRemainVisible() throws Exception {
    event("message.delta", "m", "途中");
    output.accept("llm.request.started", BackendClient.JSON.readTree("{\"feature\":\"chat\"}"));
    output.accept("task.started", BackendClient.JSON.readTree("{\"taskId\":\"task\"}"));
    event("message.delta", "m", "続き");
    event("message.completed", "m", "途中続き");
    assertEquals(
        List.of(
            "途中続き",
            "llm.request.started: {\"feature\":\"chat\"}",
            "task.started: {\"taskId\":\"task\"}"),
        lines);
  }

  @Test
  void llmAndStagnationEventsDoNotSplitTheReportedAnswer() throws Exception {
    event("message.started", "m", "");
    event("message.delta", "m", "現実");
    output.accept("llm.response.completed", BackendClient.JSON.readTree("{\"durationMs\":72342}"));
    event("message.delta", "m", "的な");
    output.accept(
        "stagnation.updated",
        BackendClient.JSON.readTree("{\"consecutiveNoProgressIterations\":1}"));
    assertTrue(lines.isEmpty());
    event("message.delta", "m", "使い方だと思うよ。");
    event("message.completed", "m", "現実的な使い方だと思うよ。");
    assertEquals(
        List.of(
            "現実的な使い方だと思うよ。",
            "llm.response.completed: {\"durationMs\":72342}",
            "stagnation.updated: {\"consecutiveNoProgressIterations\":1}"),
        lines);
  }

  @Test
  void notificationsAfterAnAnswerNewlineStillWaitForCompletion() throws Exception {
    event("message.delta", "m", "最初の行\n");
    output.accept("stagnation.updated", BackendClient.JSON.createObjectNode());
    assertEquals(List.of("最初の行"), lines);
    event("message.delta", "m", "次の行");
    event("message.completed", "m", "最初の行\n次の行");
    assertEquals(List.of("最初の行", "次の行", "stagnation.updated: {}"), lines);
  }

  @Test
  void cancellationFlushesPendingAnswerAndNoticesBeforeTheNextRun() throws Exception {
    event("message.delta", "m", "途中");
    output.accept("stagnation.updated", BackendClient.JSON.createObjectNode());
    output.accept("agent.run.cancelled", BackendClient.JSON.createObjectNode());
    event("message.completed", "next", "次の回答");
    assertEquals(
        List.of("途中", "stagnation.updated: {}", "[agent.run.cancelled] 原因: 原因の詳細がありません。", "次の回答"),
        lines);
  }

  @Test
  void approvalNotificationDisplaysTheExactContinueCommandOnItsOwnLine() {
    var payload = BackendClient.JSON.createObjectNode();
    payload.put("kind", "APPROVAL_REQUIRED");
    payload.put(
        "message",
        "承認待ち: runCommand\nこの操作を承認して続行するには、次のコマンドを入力してください:\n/approval approve ca880320-1482-4d84-8fb9-53c0b3234997 --continue");
    output.accept("attention.required", payload);
    assertEquals(1, lines.size());
    assertTrue(lines.getFirst().startsWith("+-- [APPROVAL REQUIRED]"));
    assertTrue(
        lines
            .getFirst()
            .contains("\n/approval approve ca880320-1482-4d84-8fb9-53c0b3234997 --continue\n"));
  }

  @Test
  void detachFlushesAnUnfinishedLineOnce() throws Exception {
    event("message.delta", "m", "未完了");
    output.accept("llm.response.completed", BackendClient.JSON.createObjectNode());
    output.flush();
    output.flush();
    assertEquals(List.of("未完了", "llm.response.completed: {}"), lines);
  }

  @Test
  void toolExecutionRemainsVisibleAtAnExplicitBoundary() throws Exception {
    event("message.delta", "m", "確認します。");
    output.accept("llm.response.completed", BackendClient.JSON.createObjectNode());
    output.accept("tool.started", BackendClient.JSON.createObjectNode());
    assertEquals(List.of("確認します。", "llm.response.completed: {}", "tool.started: {}"), lines);
  }
}
