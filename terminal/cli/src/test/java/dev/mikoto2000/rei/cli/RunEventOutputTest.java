package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunEventOutputTest {
  private final List<String> lines = new ArrayList<>();
  private final RunEventOutput output = new RunEventOutput(lines::add);

  private void event(String type, String id, String text) throws Exception {
    var payload = BackendClient.JSON.createObjectNode();
    payload.put(type.startsWith("thinking.") ? "thinkingId" : "messageId", id);
    payload.put(type.endsWith(".delta") ? "delta" : "text", text);
    output.accept(type, payload);
  }

  @Test void answerChunksJoinAndPreserveOnlyContentNewlines() throws Exception {
    event("message.started", "m", "");
    event("message.delta", "m", "いい");
    event("message.delta", "m", "質問\r");
    assertTrue(lines.isEmpty());
    event("message.delta", "m", "\nですね。\n最後");
    assertEquals(List.of("いい質問", "ですね。"), lines);
    event("message.completed", "m", "いい質問\r\nですね。\n最後");
    assertEquals(List.of("いい質問", "ですね。", "最後"), lines);
  }

  @Test void thinkingIsVisibleAndCompletedTextIsNotRepeated() throws Exception {
    event("thinking.started", "t", "");
    event("thinking.delta", "t", "考え");
    event("thinking.delta", "t", "中です");
    event("thinking.completed", "t", "考え中です");
    event("message.completed", "m", "回答です");
    assertEquals(List.of("[thinking]", "考え中です", "回答です"), lines);
  }

  @Test void completedOnlyThinkingAndAnswerAreVisible() throws Exception {
    event("thinking.completed", "t", "検討結果");
    event("message.completed", "m", "回答\n続き");
    assertEquals(List.of("[thinking]", "検討結果", "回答", "続き"), lines);
  }

  @Test void otherEventsWaitUntilAnswerCompletesAndRemainVisible() throws Exception {
    event("message.delta", "m", "途中");
    output.accept("llm.request.started", BackendClient.JSON.readTree("{\"feature\":\"chat\"}"));
    output.accept("task.started", BackendClient.JSON.readTree("{\"taskId\":\"task\"}"));
    event("message.delta", "m", "続き");
    event("message.completed", "m", "途中続き");
    assertEquals(List.of("途中続き", "llm.request.started: {\"feature\":\"chat\"}",
        "task.started: {\"taskId\":\"task\"}"), lines);
  }

  @Test void llmAndStagnationEventsDoNotSplitTheReportedAnswer() throws Exception {
    event("message.started", "m", "");
    event("message.delta", "m", "現実");
    output.accept("llm.response.completed", BackendClient.JSON.readTree("{\"durationMs\":72342}"));
    event("message.delta", "m", "的な");
    output.accept("stagnation.updated", BackendClient.JSON.readTree("{\"consecutiveNoProgressIterations\":1}"));
    assertTrue(lines.isEmpty());
    event("message.delta", "m", "使い方だと思うよ。");
    event("message.completed", "m", "現実的な使い方だと思うよ。");
    assertEquals(List.of("現実的な使い方だと思うよ。", "llm.response.completed: {\"durationMs\":72342}",
        "stagnation.updated: {\"consecutiveNoProgressIterations\":1}"), lines);
  }

  @Test void notificationsAfterAnAnswerNewlineStillWaitForCompletion() throws Exception {
    event("message.delta", "m", "最初の行\n");
    output.accept("stagnation.updated", BackendClient.JSON.createObjectNode());
    assertEquals(List.of("最初の行"), lines);
    event("message.delta", "m", "次の行");
    event("message.completed", "m", "最初の行\n次の行");
    assertEquals(List.of("最初の行", "次の行", "stagnation.updated: {}"), lines);
  }

  @Test void cancellationFlushesPendingAnswerAndNoticesBeforeTheNextRun() throws Exception {
    event("message.delta", "m", "途中");
    output.accept("stagnation.updated", BackendClient.JSON.createObjectNode());
    output.accept("agent.run.cancelled", BackendClient.JSON.createObjectNode());
    event("message.completed", "next", "次の回答");
    assertEquals(List.of("途中", "stagnation.updated: {}", "agent.run.cancelled: {}", "次の回答"), lines);
  }

  @Test void approvalNotificationDisplaysTheExactContinueCommandOnItsOwnLine() {
    var payload = BackendClient.JSON.createObjectNode();
    payload.put("kind", "APPROVAL_REQUIRED");
    payload.put("message", "承認待ち: runCommand\nこの操作を承認して続行するには、次のコマンドを入力してください:\n/approval approve ca880320-1482-4d84-8fb9-53c0b3234997 --continue");
    output.accept("attention.required", payload);
    assertEquals(List.of("[attention] APPROVAL_REQUIRED", "承認待ち: runCommand",
        "この操作を承認して続行するには、次のコマンドを入力してください:",
        "/approval approve ca880320-1482-4d84-8fb9-53c0b3234997 --continue"), lines);
  }

  @Test void detachFlushesAnUnfinishedLineOnce() throws Exception {
    event("message.delta", "m", "未完了");
    output.accept("llm.response.completed", BackendClient.JSON.createObjectNode());
    output.flush();
    output.flush();
    assertEquals(List.of("未完了", "llm.response.completed: {}"), lines);
  }

  @Test void toolExecutionRemainsVisibleAtAnExplicitBoundary() throws Exception {
    event("message.delta", "m", "確認します。");
    output.accept("llm.response.completed", BackendClient.JSON.createObjectNode());
    output.accept("tool.started", BackendClient.JSON.createObjectNode());
    assertEquals(List.of("確認します。", "llm.response.completed: {}", "tool.started: {}"), lines);
  }
}
