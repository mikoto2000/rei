package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 検索順位付け予算の結果と状態の契約を検証するテスト。 */
class SearchRankingBudgetTest {

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void realToolDispatchRetainsFailureHistoryAndContinuesInTheSameRun(boolean resumable)
      throws Exception {
    write("Target.java", "class Target {}");
    var projects = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    org.mockito.Mockito.when(projects.currentProject()).thenReturn(root);
    var tools = new Tools(projects);
    write("A.java", "class A {}");
    write("B.java", "class B {}");
    var events = new java.util.ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var factory = new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC());
    var context =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "budget-run",
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2, 20),
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(root, null),
            factory,
            events::add);
    var provider =
        org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build();
    var callback =
        java.util.Arrays.stream(provider.getToolCallbacks())
            .filter(tool -> tool.getToolDefinition().name().equals("searchAndRead"))
            .findFirst()
            .orElseThrow();
    var options =
        org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
            .toolCallbacks(
                new dev.mikoto2000.rei.event.ToolEventCallbackDecorator(
                    callback, factory, events::add))
            .toolContext(
                java.util.Map.of(
                    dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, context))
            .build();
    var prompt = new org.springframework.ai.chat.prompt.Prompt("Find Target", options);
    var loop = new dev.mikoto2000.rei.core.chat.ToolLoopSupport();
    var failed =
        resumable
            ? loop.executeResumable(prompt, searchCall("broad", 3), context)
            : loop.execute(prompt, searchCall("broad", 3));
    String feedback =
        ((org.springframework.ai.chat.messages.ToolResponseMessage)
                failed.conversationHistory().getLast())
            .getResponses()
            .getFirst()
            .responseData();
    assertTrue(feedback.contains("file_result_budget_exceeded"));
    assertFalse(failed.returnDirect());
    var next = new org.springframework.ai.chat.prompt.Prompt(failed.conversationHistory(), options);
    var success =
        resumable
            ? loop.executeResumable(next, searchCall("narrow", 1), context)
            : loop.execute(next, searchCall("narrow", 1));
    String output =
        ((org.springframework.ai.chat.messages.ToolResponseMessage)
                success.conversationHistory().getLast())
            .getResponses()
            .getFirst()
            .responseData();
    assertTrue(output.contains("Target.java"));
    assertFalse(dev.mikoto2000.rei.core.stagnation.ProgressEvaluator.failedResult(output));
    assertTrue(success.conversationHistory().containsAll(failed.conversationHistory()));
    assertEquals(
        List.of(
            dev.mikoto2000.rei.event.AgentEventType.TOOL_STARTED,
            dev.mikoto2000.rei.event.AgentEventType.TOOL_FAILED,
            dev.mikoto2000.rei.event.AgentEventType.TOOL_STARTED,
            dev.mikoto2000.rei.event.AgentEventType.TOOL_COMPLETED),
        events.stream().map(dev.mikoto2000.rei.event.AgentEvent::type).toList());
  }

  private org.springframework.ai.chat.model.ChatResponse searchCall(String id, int maxFiles) {
    String args = "{\"request\":{\"queries\":[{\"pattern\":\"class\",\"baseDir\":\".\",\"fixedString\":true,"
        + "\"includeGlob\":\"" + (maxFiles == 1 ? "Target.java" : "*.java") + "\"}],"
        + "\"maxFiles\":" + maxFiles + ",\"contextLines\":0,\"maxBytes\":768,\"maxTokens\":1000}}";
    return new org.springframework.ai.chat.model.ChatResponse(
        List.of(
            new org.springframework.ai.chat.model.Generation(
                org.springframework.ai.chat.messages.AssistantMessage.builder()
                    .toolCalls(
                        List.of(
                            new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                id, "function", "searchAndRead", args)))
                    .build())));
  }

  @Test
  void searchStatisticsCountOnlyFilesPresentBeforeReading() throws Exception {
    write("Target.java", "class Target {}");
    var clock = java.time.Clock.systemUTC();
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var events = new java.util.ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    bus.subscribe(events::add);
    var working = new dev.mikoto2000.rei.core.working.WorkingSet(10, clock,
        new dev.mikoto2000.rei.event.AgentEventFactory(clock), bus);
    var tools = new Tools(null, null, null, clock, working) {
      @Override
      List<String> listFile(String base, Path directory) { return List.of("Target.java"); }
    };
    var request = new Tools.SearchAndReadRequest(List.of(query("Target")), 0, 1);
    tools.searchAndRead(request, root);
    tools.searchAndRead(request, root);
    var completed = events.stream()
        .filter(event -> event.type() == dev.mikoto2000.rei.event.AgentEventType.WORKING_SET_SEARCH_COMPLETED)
        .map(event -> (dev.mikoto2000.rei.event.WorkingSetSearchCompletedPayload) event.payload()).toList();
    assertEquals(0, completed.getFirst().alreadyPresentCount());
    assertEquals(1, completed.getLast().alreadyPresentCount());
  }

  @Test
  void aggregateBudgetIncludesManyFileErrorsAndMarksOmittedFiles() throws Exception {
    write("Target.java", "class Target {}");
    var paths = new java.util.ArrayList<String>();
    paths.add("Target.java");
    for (int i = 0; i < 32; i++) paths.add("missing-" + i + "-" + "x".repeat(100) + ".txt");
    var result =
        tools(paths)
            .searchAndRead(
                new Tools.SearchAndReadRequest(
                    List.of(query("Target")), 0, 2, 2048, 1000, 100, List.of()),
                root);
    assertEquals("Target.java", result.getFirst().path());
    assertFalse(result.getFirst().sections().isEmpty());
    assertTrue(result.getFirst().filesTruncated());
    assertTrue(FileResultBudget.fits(result, 2048, 1000));
    assertTrue(result.size() < paths.size());
  }

  @Test
  void aggregateShrinksContentAndRetainsVersionBoundContinuation() throws Exception {
    var source =
        new Tools.SearchAndReadResult(
            "a.txt",
            List.of(new Tools.SearchMatch(0, 1, "hit" + "日本語".repeat(500))),
            List.of(new Tools.ReadSection(1, 1, List.of("hit" + "日本語".repeat(500)), false)),
            null,
            false,
            false,
            "version",
            null);
    var result = SearchResultBudget.fitAll(List.of(source), 2048, 600, java.util.Map.of());
    assertTrue(FileResultBudget.fits(result, 2048, 600));
    assertTrue(result.getFirst().truncated());
    assertEquals("version", result.getFirst().nextRead().expectedVersion());
    assertTrue(result.getFirst().nextRead().offset() >= 0);
  }

  @Test
  void impossibleMetadataHasTypedFailureInsteadOfAnEmptySuccessfulSearch() {
    var result =
        new Tools.SearchAndReadResult("x".repeat(2000), List.of(), List.of(), null, false, false);
    assertThrows(
        dev.mikoto2000.rei.toolcontract.FileResultBudgetExceededException.class,
        () -> SearchResultBudget.fitAll(List.of(result), 256, 256, java.util.Map.of()));
  }

  @Test
  void tinyPerFileBudgetIsRecoverableAndANarrowerSearchSucceeds() throws Exception {
    write("Target.java", "class Target {}");
    var tools = tools(List.of("Target.java"));
    assertThrows(
        dev.mikoto2000.rei.toolcontract.FileResultBudgetExceededException.class,
        () ->
            tools.searchAndRead(
                new Tools.SearchAndReadRequest(
                    List.of(query("Target")), 0, 1, 256, 256, 100, List.of()),
                root));
    assertEquals(
        "Target.java",
        tools
            .searchAndRead(
                new Tools.SearchAndReadRequest(List.of(query("class Target")), 0, 1), root)
            .getFirst()
            .path());
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code tools} の条件を再現するための補助情報を返す。 */
  Tools tools(List<String> paths) {
    return new Tools() {
      /** {@code listFile} の条件を再現するための補助情報を返す。 */
      @Override
      List<String> listFile(String base, Path directory) {
        return paths;
      }
    };
  }

  /** {@code query} の条件を再現するための補助情報を返す。 */
  Tools.GrepQuery query(String text) {
    return new Tools.GrepQuery(text, ".", false, true, false, false, 0, 0, null, true, null, null);
  }

  /** 検証用に、指定された内容を書き込み先へ反映する。 */
  void write(String path, String text) throws Exception {
    Files.writeString(root.resolve(path), text);
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void exactFilenameAndAstDeclarationBeatBodyReferences() throws Exception {
    write("a.txt", "UserService reference");
    write("UserService.java", "class UserService {}");
    var result =
        tools(List.of("a.txt", "UserService.java"))
            .searchAndRead(
                new Tools.SearchAndReadRequest(List.of(query("UserService")), 0, 2), root);
    assertEquals("UserService.java", result.getFirst().path());
    assertTrue(result.getFirst().matchedBy().contains("symbol-exact"));
    assertTrue(result.getFirst().score() > result.getLast().score());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void methodDeclarationsAndExplicitPathsAreRankedWithoutLlmCalls() throws Exception {
    write("A.java", "class A { void reference(){ z.execute(); } }");
    write("Z.java", "class Z { void execute() {} }");
    var tools = tools(List.of("A.java", "Z.java"));
    var method =
        tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(query("execute")), 0, 2), root);
    assertEquals("Z.java", method.getFirst().path());
    assertTrue(method.getFirst().matchedBy().contains("symbol-exact"));
    var explicit =
        tools.searchAndRead(
            new Tools.SearchAndReadRequest(
                List.of(query("execute")), 0, 2, 8192, 2000, 500, List.of("A.java")),
            root);
    assertEquals("A.java", explicit.getFirst().path());
    assertTrue(explicit.getFirst().matchedBy().contains("explicit-path"));
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void aHugeEarlyHitFileCannotHideTheLaterExactTargetOrConsumeItsBudget() throws Exception {
    write("a.txt", "Target reference\n".repeat(2000));
    write("Target.java", "class Target { void work(){} }");
    var result =
        tools(List.of("a.txt", "Target.java"))
            .searchAndRead(
                new Tools.SearchAndReadRequest(
                    List.of(query("Target")), 25, 2, 4096, 1200, 500, List.of()),
                root);
    assertEquals(2, result.size());
    assertEquals("Target.java", result.getFirst().path());
    assertFalse(result.getFirst().sections().isEmpty());
    assertFalse(result.getLast().sections().isEmpty());
    var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
    assertTrue(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096);
    assertTrue(
        dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative().text(json) <= 1200);
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void japaneseTokenLimitsAndContinuationAreAppliedToTheWholeJsonResult() throws Exception {
    write("a.txt", "ヒット" + "日本語".repeat(3000));
    write("b.txt", "ヒット" + "日本語".repeat(3000));
    var result =
        tools(List.of("a.txt", "b.txt"))
            .searchAndRead(
                new Tools.SearchAndReadRequest(
                    List.of(query("ヒット")), 0, 2, 8192, 1200, 100, List.of()),
                root);
    assertEquals(2, result.size());
    assertTrue(result.stream().allMatch(Tools.SearchAndReadResult::truncated));
    assertTrue(result.stream().allMatch(item -> item.nextRead() != null));
    var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
    assertTrue(
        dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative().text(json) <= 1200);
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void tiedResultsAreStableAndWorkingFilesHaveAnExplicitReason() throws Exception {
    write("a.txt", "hit");
    write("z.txt", "hit");
    var tools = tools(List.of("z.txt", "a.txt"));
    var request = new Tools.SearchAndReadRequest(List.of(query("hit")), 0, 2);
    assertEquals(
        List.of("a.txt", "z.txt"),
        tools.searchAndRead(request, root).stream().map(Tools.SearchAndReadResult::path).toList());
    assertEquals(
        List.of("a.txt", "z.txt"),
        tools.searchAndRead(request, root).stream().map(Tools.SearchAndReadResult::path).toList());
    var active = tools(List.of("a.txt", "z.txt"));
    active.workingSet().recordRead(root.resolve("z.txt"));
    assertEquals("z.txt", active.searchAndRead(request, root).getFirst().path());
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void inventoryCannotEnumerateOutsideTheProject() throws Exception {
    assertThrows(java.io.IOException.class, () -> new Tools().listFile("..", root));
    assertThrows(java.io.IOException.class, () -> new Tools().listFile(".git", root));
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void noMatchesAreNormalButInvalidBudgetsAreExplicitErrors() throws Exception {
    write("a.txt", "nothing");
    var tools = tools(List.of("a.txt"));
    assertTrue(
        tools
            .searchAndRead(new Tools.SearchAndReadRequest(List.of(query("hit")), 0, 2), root)
            .isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tools.searchAndRead(
                new Tools.SearchAndReadRequest(
                    List.of(query("hit")), 0, 2, 0, 1000, 500, List.of()),
                root));
  }
}
