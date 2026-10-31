package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 実行イベント出力の結果と状態の契約を検証するテスト。 */
class RunEventOutputTest {
  @Test
  void displaysFileReadPageMetadataWithCallIdWithoutShorteningIt() {
    String summary = "[readMultiFile] " + "page-metadata ".repeat(30);
    output.accept(
        "tool.completed",
        BackendClient.JSON
            .createObjectNode()
            .put("toolName", "readMultiFile")
            .put("toolCallId", "read-1")
            .put("duration", 12)
            .put("resultSummary", summary));
    assertEquals(List.of("  ✓ readMultiFile (12 ms)", "    call=read-1 " + summary), lines);
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 軽量CLIでの対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void nullErrorCodeDoesNotDisplayNullAsAReason() throws Exception {
    output.accept(
        "agent.run.failed",
        BackendClient.JSON.readTree(
            "{\"error\":{\"message\":null,\"errorType\":\"IOException\",\"code\":null}}"));
    assertEquals(List.of("[agent] failed: IOException"), lines);
  }

  /** 軽量CLIでの対話で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void runCancellationDisplaysRedactedReasonAndCode() throws Exception {
    output.accept(
        "agent.run.cancelled",
        BackendClient.JSON.readTree(
            "{\"error\":{\"message\":\"approval denied token=hidden\",\"code\":\"approval_denied\"}}"));
    assertEquals(
        List.of("[agent] cancelled: [approval_denied] approval denied token=[REDACTED]"), lines);
  }

  /** 空白のツール失敗情報引き続きについて、例外種別を伝えることを確認する。 */
  @Test
  void blankToolFailureStillReportsExceptionType() throws Exception {
    output.accept(
        "tool.failed",
        BackendClient.JSON.readTree(
            "{\"toolName\":\"readFile\",\"error\":{\"message\":\"\",\"errorType\":\"AccessDeniedException\"}}"));
    assertEquals(List.of("  ✗ readFile: AccessDeniedException"), lines);
  }

  /** 軽量CLIでの対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void permissionFailureShowsItsSpecificReasonWithoutJsonEscapes() throws Exception {
    output.accept(
        "tool.failed",
        BackendClient.JSON.readTree(
            "{\"toolName\":\"delegateTask\",\"error\":{\"message\":\"PERMISSION_DENIED (ACCOUNT_REQUIRED: accountRef を指定してください。)\"}}"));
    assertEquals(
        List.of("  ✗ delegateTask: PERMISSION_DENIED (ACCOUNT_REQUIRED: accountRef を指定してください。)"),
        lines);
  }

  /** 軽量CLIでの対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void delegatedFailureDisplaysItsCauseAsReadableText() throws Exception {
    output.accept(
        "subagent.failed",
        BackendClient.JSON.readTree(
            "{\"agentId\":\"reviewer\",\"status\":\"FAILED\",\"subAgentRunId\":\"child\",\"failureReason\":\"SEND_LIMIT: 資料を分割してください。\"}"));
    assertEquals(
        List.of(
            "[subagent] reviewer failed (0.0 s) [child]",
            "[subagent] 原因: SEND_LIMIT: 資料を分割してください。"),
        lines);
  }

  /** 検証に使う行一覧。 */
  private final List<String> lines = new ArrayList<>();

  /** 検証に使う見出し一覧。 */
  private final List<String> headings = new ArrayList<>();

  /** 検証に使う出力。 */
  private final RunEventOutput output =
      new RunEventOutput(
          line -> {
            if (line.startsWith("=== ")) headings.add(line);
            else lines.add(line);
          });

  /** 検証に使う記録日時。 */
  private final java.time.Instant timestamp = java.time.Instant.parse("2026-10-12T11:34:56Z");

  @Test
  void runToolAndLlmProgressUseReadableShellLinesWithMetrics() throws Exception {
    output.accept("agent.run.started", BackendClient.JSON.createObjectNode());
    output.accept(
        "llm.request.started",
        BackendClient.JSON.readTree("{\"requestId\":\"r\",\"feature\":\"chat\"}"));
    output.accept("llm.response.first_token", BackendClient.JSON.readTree("{\"durationMs\":123}"));
    event("message.delta", "m", "確認します。");
    output.accept(
        "llm.response.completed",
        BackendClient.JSON.readTree("{\"requestId\":\"r\",\"durationMs\":600}"));
    output.accept(
        "tool.started",
        BackendClient.JSON.readTree(
            "{\"toolName\":\"readFile\",\"argumentsSummary\":\"a.txt\\n line=1\"}"));
    output.accept(
        "tool.completed",
        BackendClient.JSON.readTree("{\"toolName\":\"readFile\",\"duration\":25}"));
    output.accept("checkpoint.saved", BackendClient.JSON.createObjectNode());
    output.accept(
        "agent.run.completed",
        BackendClient.JSON.readTree(
            "{\"duration\":2500,\"completionTokens\":30,\"timeToFirstTokenMillis\":123.0,\"outputTokensPerSecond\":20.0,\"endToEndTokensPerSecond\":12.0}"));
    assertEquals(
        List.of(
            "[agent] running",
            "[llm] request sent (chat)",
            "[llm] first token (123 ms)",
            "確認します。",
            "[llm] response received (chat, 600 ms)",
            "  → readFile a.txt line=1",
            "  ✓ readFile (25 ms)",
            "[agent] completed (2.5 s, 30 tokens, TTFT 123.0 ms, output 20.0 tok/s, end-to-end 12.0 tok/s)"),
        lines);
  }

  /** 検証で使用するイベントを、指定された条件に合わせて用意する。 */
  private void event(String type, String id, String text) throws Exception {
    var payload = BackendClient.JSON.createObjectNode();
    payload.put(type.startsWith("thinking.") ? "thinkingId" : "messageId", id);
    payload.put(type.endsWith(".delta") ? "delta" : "text", text);
    output.accept(type, payload, timestamp);
  }

  /** 見出し一覧について、シェル形式を利用することを確認する。 */
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

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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

  /** 軽量CLIでの対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
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

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void thinkingIsVisibleAndCompletedTextIsNotRepeated() throws Exception {
    event("thinking.started", "t", "");
    event("thinking.delta", "t", "考え");
    event("thinking.delta", "t", "中です");
    event("thinking.completed", "t", "考え中です");
    event("message.completed", "m", "回答です");
    assertEquals(List.of("考え中です", "回答です"), lines);
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completedOnlyThinkingAndAnswerAreVisible() throws Exception {
    event("thinking.completed", "t", "検討結果");
    event("message.completed", "m", "回答\n続き");
    assertEquals(List.of("検討結果", "回答", "続き"), lines);
  }

  /** 軽量CLIでの対話で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void otherEventsWaitUntilAnswerCompletesAndRemainVisible() throws Exception {
    event("message.delta", "m", "途中");
    output.accept("llm.request.started", BackendClient.JSON.readTree("{\"feature\":\"chat\"}"));
    output.accept("task.started", BackendClient.JSON.readTree("{\"taskId\":\"task\"}"));
    event("message.delta", "m", "続き");
    event("message.completed", "m", "途中続き");
    assertEquals(List.of("途中続き", "[llm] request sent (chat)"), lines);
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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
            "[llm] response received (, 72342 ms)",
            "[stagnation] no progress: 1/0"),
        lines);
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void notificationsAfterAnAnswerNewlineStillWaitForCompletion() throws Exception {
    event("message.delta", "m", "最初の行\n");
    output.accept("stagnation.updated", BackendClient.JSON.createObjectNode());
    assertEquals(List.of("最初の行"), lines);
    event("message.delta", "m", "次の行");
    event("message.completed", "m", "最初の行\n次の行");
    assertEquals(List.of("最初の行", "次の行", "[stagnation] no progress: 0/0"), lines);
  }

  /** 軽量CLIでの対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationFlushesPendingAnswerAndNoticesBeforeTheNextRun() throws Exception {
    event("message.delta", "m", "途中");
    output.accept("stagnation.updated", BackendClient.JSON.createObjectNode());
    output.accept("agent.run.cancelled", BackendClient.JSON.createObjectNode());
    event("message.completed", "next", "次の回答");
    assertEquals(
        List.of("途中", "[stagnation] no progress: 0/0", "[agent] cancelled: 原因の詳細がありません。", "次の回答"),
        lines);
  }

  /** 軽量CLIでの対話の操作が、指定された権限・承認条件に従うことを確認する。 */
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

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void detachFlushesAnUnfinishedLineOnce() throws Exception {
    event("message.delta", "m", "未完了");
    output.accept("llm.response.completed", BackendClient.JSON.createObjectNode());
    output.flush();
    output.flush();
    assertEquals(List.of("未完了", "[llm] response received (, 0 ms)"), lines);
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void toolExecutionRemainsVisibleAtAnExplicitBoundary() throws Exception {
    event("message.delta", "m", "確認します。");
    output.accept("llm.response.completed", BackendClient.JSON.createObjectNode());
    output.accept("tool.started", BackendClient.JSON.createObjectNode());
    assertEquals(List.of("確認します。", "[llm] response received (, 0 ms)", "  → "), lines);
  }
}
