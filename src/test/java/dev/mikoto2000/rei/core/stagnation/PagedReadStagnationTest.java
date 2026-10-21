package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ExecutionProgressPayload;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Paged読み取り停滞の結果と状態の契約を検証するテスト。 */
class PagedReadStagnationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper();

  /** 実行の停滞への対処で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void sixtyForwardPagesDoNotStallCompleteWorkOrReplenishModelBudget() {
    var events = new ArrayList<AgentEvent>();
    var context = context(new OutputLimitRunBudget(2, 64), events);
    for (int page = 0; page < 60; page++) {
      context.consumeNextLlmCall();
      read(context, page * 2 + 1, List.of("line", "line"));
      assertThat(context.requestReplan()).isNull();
    }
    assertThat(context.progressSnapshot().acquisitionOnlyIterations()).isZero();
    assertThat(context.detector().stagnationCount()).isZero();
    assertThat(context.progressVersion()).isEqualTo(60);
    assertThat(events)
        .noneMatch(
            event ->
                event.payload() instanceof ExecutionProgressPayload payload
                    && payload.evidence() != null
                    && payload.evidence().kind() != ProgressEvent.NEW_INFORMATION);
    assertThat(events).noneMatch(event -> event.type() == AgentEventType.STAGNATION_DETECTED);
    for (int call = 0; call < 4; call++) context.consumeNextLlmCall();
    assertThatThrownBy(context::consumeNextLlmCall)
        .hasMessageContaining("LLM_CALL_BUDGET_EXCEEDED");
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void forwardFragmentsOfOneLongLineCountOnlyOncePerDeliveredOffset() {
    var context = context(new OutputLimitRunBudget(2, 100), new ArrayList<>());
    String lastArguments = "", lastResult = "";
    for (int page = 0; page < 60; page++) {
      var request =
          json.createObjectNode()
              .put("path", "A.md")
              .put("startLine", 1)
              .put("expectedVersion", "v1")
              .put("offset", page * 3);
      var result =
          json.createObjectNode()
              .put("path", "A.md")
              .put("version", "v1")
              .put("startLine", 1)
              .put("truncated", true);
      result.set("content", json.valueToTree(List.of("aaa")));
      result.set("nextRead", request.deepCopy().put("offset", (page + 1) * 3));
      lastArguments = "{\"files\":[" + request + "]}";
      lastResult = "[" + result + "]";
      iteration(context, "readMultiFile", lastArguments, lastResult);
    }
    assertThat(context.detector().stagnationCount()).isZero();
    assertThat(context.progressSnapshot().information().files())
        .isEmpty(); // No whole line was delivered.
    for (int repeat = 0; repeat < 4; repeat++)
      iteration(context, "readMultiFile", lastArguments, lastResult);
    assertThat(context.detector().isStagnant()).isTrue();
    assertThat(context.progressVersion()).isEqualTo(60);
    iteration(
        context,
        "readMultiFile",
        lastArguments.replace("\"offset\":177", "\"offset\":999"),
        lastResult);
    assertThat(context.progressVersion()).isEqualTo(60);
  }

  /** 実行の停滞への対処で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateRangesAndEmptyContinuationPagesStillStall() {
    var context = context(new OutputLimitRunBudget(2, 100), new ArrayList<>());
    read(context, 1, List.of("one", "two"));
    long version = context.progressVersion();
    for (int repeat = 0; repeat < 4; repeat++) {
      // Different request spelling does not add delivered file content.
      iteration(
          context,
          "readMultiFile",
          "{\"unused\":" + repeat + "}",
          fileResult(1, List.of("one", "two")));
    }
    assertThat(context.detector().isStagnant()).isTrue();
    assertThat(context.progressVersion()).isEqualTo(version);

    var empty = context(new OutputLimitRunBudget(2, 100), new ArrayList<>());
    for (int repeat = 0; repeat < 4; repeat++)
      iteration(
          empty,
          "readMultiFile",
          "{}",
          "[{\"path\":\"A.md\",\"version\":\"v1\",\"startLine\":1,\"content\":[],\"truncated\":true,"
              + "\"nextRead\":{\"path\":\"A.md\",\"startLine\":1,\"offset\":"
              + repeat
              + "}}]");
    assertThat(empty.detector().isStagnant()).isTrue();
    assertThat(empty.progressVersion()).isZero();
  }

  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void paginationPausesAcquisitionHeuristicWithoutErasingPreviouslySpentAcquisition() {
    var events = new ArrayList<AgentEvent>();
    var context = context(new OutputLimitRunBudget(2, 100), events);
    for (int result = 0; result < 31; result++)
      iteration(context, "findFile", "{}", "[\"file-" + result + "\"]");
    for (int page = 0; page < 40; page++) read(context, page + 1, List.of("line"));
    assertThat(context.progressSnapshot().acquisitionOnlyIterations()).isEqualTo(31);
    assertThat(context.detector().stagnationCount()).isZero();
    iteration(context, "findFile", "{}", "[\"new discovery\"]");
    assertThat(context.progressSnapshot().acquisitionOnlyIterations()).isEqualTo(32);
    assertThat(context.detector().stagnationCount()).isEqualTo(1);
    assertThat(events)
        .anyMatch(
            event ->
                event.payload() instanceof ExecutionProgressPayload payload
                    && "WORK_STALLED".equals(payload.reason()));
    // Even an already exhausted heuristic cannot label a newly delivered range as WORK_STALLED.
    read(context, 41, List.of("line"));
    assertThat(context.detector().stagnationCount()).isZero();
    assertThat(context.progressSnapshot().acquisitionOnlyIterations()).isEqualTo(32);
  }

  /** 復元済み記録台帳について、重複の有無ページ一覧を保持することを確認する。 */
  @Test
  void restoredLedgerRetainsDuplicatePagesAndAllowsOnlyUnseenContinuation() {
    var original = context(new OutputLimitRunBudget(2, 20), new ArrayList<>());
    read(original, 1, List.of("one", "two"));
    original.consumeNextLlmCall();
    var resumed = context(new OutputLimitRunBudget(2, 20), new ArrayList<>());
    resumed.restoreProgress(original.progressSnapshot());
    for (int repeat = 0; repeat < 4; repeat++) read(resumed, 1, List.of("one", "two"));
    assertThat(resumed.detector().isStagnant()).isTrue();
    assertThat(resumed.progressVersion()).isZero();
    read(resumed, 3, List.of("three"));
    assertThat(resumed.detector().stagnationCount()).isZero();
    assertThat(resumed.progressVersion()).isEqualTo(1);
    assertThat(resumed.budgetSnapshot()).isEqualTo(original.budgetSnapshot());
  }

  /** 実行の停滞への対処で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void unknownHistoricalRangesNeverBecomeFalseProgressOrUseUnboundedReplanNotices() {
    var context = context(new OutputLimitRunBudget(2, 100), new ArrayList<>());
    context.evaluator().restoreInformation(null);
    int notices = 0;
    for (int page = 0; page < 60; page++) {
      read(context, page + 1, List.of("line"));
      String notice = context.requestReplan();
      if (notice != null) {
        assertThat(notice).contains("INFORMATION_UNKNOWN");
        notices++;
      }
    }
    assertThat(notices).isEqualTo(2);
    assertThat(context.progressVersion()).isZero();
    assertThat(context.progressSnapshot().unknownIterations()).isEqualTo(60);
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void rawPagesUseValidatedRangesIncludingLegacyQuotedResponses() throws Exception {
    var context = context(new OutputLimitRunBudget(2, 100), new ArrayList<>());
    String ref = UUID.randomUUID().toString(), last = "";
    for (int page = 0; page < 40; page++) {
      last =
          "toolName: delegateTask\ntoolCallId: call\nrawResultRef: "
              + ref
              + "\noffset: "
              + page
              + "\ntotalCharacters: 40\nnextOffset: "
              + (page + 1)
              + "\nhasMore: "
              + (page < 39)
              + "\nx";
      iteration(
          context, "readRawToolResult", "{}", page % 2 == 0 ? last : json.writeValueAsString(last));
    }
    assertThat(context.detector().stagnationCount()).isZero();
    assertThat(context.progressVersion()).isEqualTo(40);
    for (int repeat = 0; repeat < 4; repeat++) iteration(context, "readRawToolResult", "{}", last);
    assertThat(context.detector().isStagnant()).isTrue();
    iteration(context, "readRawToolResult", "{}", last.replace("nextOffset: 40", "nextOffset: 41"));
    assertThat(context.progressVersion()).isEqualTo(40);
    assertThat(context.progressSnapshot().unknownIterations()).isEqualTo(1);
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  private RunExecutionContext context(OutputLimitRunBudget budget, List<AgentEvent> events) {
    return new RunExecutionContext(
        "run",
        budget,
        new ProgressEvaluator(root),
        new AgentEventFactory(Clock.systemUTC()),
        events::add);
  }

  /** 検証用に、指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private void read(RunExecutionContext context, int start, List<String> lines) {
    iteration(
        context,
        "readMultiFile",
        "{\"files\":[{\"path\":\"A.md\",\"startLine\":" + start + "}]}",
        fileResult(start, lines));
  }

  /** {@code fileResult} の条件を再現するための補助情報を返す。 */
  private String fileResult(int start, List<String> lines) {
    var result =
        json.createObjectNode().put("path", "A.md").put("version", "v1").put("startLine", start);
    result.set("content", json.valueToTree(lines));
    return "[" + result + "]";
  }

  /** {@code iteration} の条件を再現するため、検証用の操作や状態を扱う。 */
  private void iteration(
      RunExecutionContext context, String tool, String arguments, String result) {
    context.beginIteration();
    context.recordTool(tool, arguments, result, context.evaluator().beforeTool(tool, arguments));
    context.endIteration();
  }
}
