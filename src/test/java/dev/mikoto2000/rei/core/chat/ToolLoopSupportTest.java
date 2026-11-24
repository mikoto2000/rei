package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** ツール繰り返し補助の結果と状態の契約を検証するテスト。 */
class ToolLoopSupportTest {
  @Test
  void missingGenerationFailsBeforePersistenceOrDispatchWithAnExplicitError() {
    var callback = persistenceCallback(new java.util.concurrent.atomic.AtomicInteger());
    var prompt = new Prompt("fixture", OpenAiChatOptions.builder().toolCallbacks(callback).build());
    assertThatThrownBy(() -> new ToolLoopSupport().execute(prompt, new ChatResponse(List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing generation for tool calls")
        .hasNoCause();
    org.mockito.Mockito.verify(callback, org.mockito.Mockito.never())
        .call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void assistantPersistenceFailurePreventsToolDispatch() {
    var store =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class);
    org.mockito.Mockito.when(
            store.save(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("Model message persistence rejected"));
    var callback = persistenceCallback(new java.util.concurrent.atomic.AtomicInteger());
    var prompt = persistencePrompt(store, callback);
    assertThatThrownBy(() -> new ToolLoopSupport().execute(prompt, persistenceResponse()))
        .hasMessage("Model message persistence rejected");
    org.mockito.Mockito.verify(callback, org.mockito.Mockito.never())
        .call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void resultPersistenceFailureStopsTheLoopAfterTheExecutedCallback() {
    var store =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class);
    var ref =
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Ref("fixture", "a".repeat(64));
    org.mockito.Mockito.when(
            store.save(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isA(
                    org.springframework.ai.chat.messages.AssistantMessage.class)))
        .thenReturn(ref);
    org.mockito.Mockito.when(
            store.save(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isA(
                    org.springframework.ai.chat.messages.ToolResponseMessage.class)))
        .thenThrow(new IllegalStateException("Model message persistence rejected"));
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var callback = persistenceCallback(calls);
    assertThatThrownBy(() ->
            new ToolLoopSupport()
                .execute(persistencePrompt(store, callback), persistenceResponse()))
        .hasMessage("Model message persistence rejected");
    assertThat(calls).hasValue(1);
    var ordered = org.mockito.Mockito.inOrder(store, callback);
    ordered
        .verify(store)
        .save(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("run"),
            org.mockito.ArgumentMatchers.eq("iteration/ASSISTANT"),
            org.mockito.ArgumentMatchers.isA(
                org.springframework.ai.chat.messages.AssistantMessage.class));
    ordered
        .verify(store)
        .publish(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(ref),
            org.mockito.ArgumentMatchers.eq(
                dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN),
            org.mockito.ArgumentMatchers.eq("run"));
    ordered
        .verify(callback)
        .call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    ordered
        .verify(store)
        .save(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("run"),
            org.mockito.ArgumentMatchers.eq("iteration/TOOL"),
            org.mockito.ArgumentMatchers.isA(
                org.springframework.ai.chat.messages.ToolResponseMessage.class));
  }

  private static ToolCallback persistenceCallback(java.util.concurrent.atomic.AtomicInteger calls) {
    var callback = org.mockito.Mockito.mock(ToolCallback.class);
    org.mockito.Mockito.when(callback.getToolDefinition())
        .thenReturn(
            ToolDefinition.builder()
                .name("fixture")
                .description("fixture")
                .inputSchema("{}")
                .build());
    org.mockito.Mockito.when(callback.getToolMetadata())
        .thenReturn(org.springframework.ai.tool.metadata.ToolMetadata.builder().build());
    org.mockito.Mockito.when(
            callback.call(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(ignored -> {
          calls.incrementAndGet();
          return "fixture result";
        });
    return callback;
  }

  private static Prompt persistencePrompt(
      dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore store, ToolCallback callback) {
    var exchange =
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(
            store,
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope(
                "project", "root", "conversation"),
            "run",
            "iteration");
    return new Prompt(
        "fixture request",
        OpenAiChatOptions.builder()
            .toolCallbacks(callback)
            .toolContext(
                Map.of(
                    dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
                    exchange))
            .build());
  }

  private static ChatResponse persistenceResponse() {
    return new ChatResponse(
        List.of(
            new org.springframework.ai.chat.model.Generation(
                org.springframework.ai.chat.messages.AssistantMessage.builder()
                    .content("fixture commentary")
                    .toolCalls(
                        List.of(
                            new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                "call_one", "function", "fixture", "{}")))
                    .build())));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "readMultiFile,CONTEXT_BUDGET_INSUFFICIENT,true",
    "readPdfPage,CONTEXT_BUDGET_INSUFFICIENT,true",
    "readMultiFile,TASK_ACCESS_DENIED,false",
    "unrelated,CONTEXT_BUDGET_INSUFFICIENT,false"
  })
  void pendingBudgetRejectionPreservesCompletedSiblingsAndRequestsRecovery(
      String tool, String code, boolean recovery) {
    var callback = org.mockito.Mockito.mock(ToolCallback.class);
    org.mockito.Mockito.when(callback.getToolDefinition())
        .thenReturn(
            ToolDefinition.builder().name(tool).description("read").inputSchema("{}").build());
    org.mockito.Mockito.when(callback.getToolMetadata())
        .thenReturn(
            org.springframework.ai.tool.metadata.ToolMetadata.builder().returnDirect(true).build());
    var rejection = new dev.mikoto2000.rei.toolcontract.ToolReadRejectedException(code, "retry");
    org.mockito.Mockito.when(
            callback.call(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(rejection);
    var saved =
        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
            "saved", tool, "saved result");
    var pending =
        new RunContinuation.PendingTools(
            "",
            List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                    "saved", "function", tool, "{}"),
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                    "retry", "function", tool, "{}")),
            List.of(saved));
    var context =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.class);
    org.mockito.Mockito.when(context.toolReadCorrection("retry", tool, rejection))
        .thenReturn(
            dev.mikoto2000.rei.core.stagnation.RunExecutionContext.toolRejectionResult(
                rejection, "retry only this read", 1));
    var result =
        new ToolLoopSupport()
            .executePending(
                new Prompt("resume", OpenAiChatOptions.builder().toolCallbacks(callback).build()),
                pending,
                context);
    var feedback =
        (org.springframework.ai.chat.messages.ToolResponseMessage)
            result.conversationHistory().getLast();
    assertThat(feedback.getResponses()).hasSize(2);
    assertThat(feedback.getResponses().getFirst()).isEqualTo(saved);
    assertThat(Boolean.TRUE.equals(feedback.getMetadata().get("rei.fileBudgetRecovery")))
        .isEqualTo(recovery);
    assertThat(result.returnDirect()).isFalse();
    org.mockito.Mockito.verify(callback, org.mockito.Mockito.times(1))
        .call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "CONTEXT_BUDGET_INSUFFICIENT",
        "REVIEW_PAGE_BUDGET_TOO_SMALL",
        "TASK_ACCESS_DENIED"
      })
  void onlyValidatedReviewBudgetRejectionsRequestRecovery(String code) {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    ToolCallback callback =
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readNextReviewWorkItem")
                .description("review read")
                .inputSchema("{}")
                .build();
          }

          public String call(String input) {
            calls.incrementAndGet();
            return "{\"error\":\"" + code + "\",\"accepted\":false,\"validationExecuted\":true}";
          }
        };
    var prompt =
        new Prompt("review", OpenAiChatOptions.builder().toolCallbacks(List.of(callback)).build());
    var response =
        new ChatResponse(
            List.of(
                new org.springframework.ai.chat.model.Generation(
                    org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    "read", "function", "readNextReviewWorkItem", "{}")))
                        .build())));
    var result = new ToolLoopSupport().execute(prompt, response);
    var feedback =
        (org.springframework.ai.chat.messages.ToolResponseMessage)
            result.conversationHistory().getLast();
    assertThat(Boolean.TRUE.equals(feedback.getMetadata().get("rei.reviewBudgetRecovery")))
        .isEqualTo(!code.equals("TASK_ACCESS_DENIED"));
    assertThat(feedback.getResponses()).hasSize(1);
    assertThat(calls.get()).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"webSearch", "webSearchAndRead"})
  void searchHttpFailureDoesNotAbortLocalFix(String searchTool) throws Exception {
    var search = org.mockito.Mockito.mock(dev.mikoto2000.rei.websearch.WebSearchService.class);
    var read = org.mockito.Mockito.mock(dev.mikoto2000.rei.websearch.WebSearchAndReadService.class);
    org.mockito.Mockito.when(search.search("query", 1))
        .thenThrow(new dev.mikoto2000.rei.websearch.WebSearchHttpException(402));
    org.mockito.Mockito.when(read.searchAndRead(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new dev.mikoto2000.rei.websearch.WebSearchHttpException(402));
    var fixed = new java.util.concurrent.atomic.AtomicBoolean();
    ToolCallback local =
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("applyTextDiff")
                .description("local fix")
                .inputSchema("{}")
                .build();
          }

          public String call(String input) {
            fixed.set(true);
            return "fixed";
          }
        };
    var events = new java.util.ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var factory = new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC());
    var provider =
        org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
            .toolObjects(new dev.mikoto2000.rei.websearch.WebSearchTools(search, read))
            .build();
    var callbacks =
        new java.util.ArrayList<ToolCallback>(
            List.of(
                new dev.mikoto2000.rei.event.ToolEventCallbackProvider(
                        provider, factory, events::add)
                    .getToolCallbacks()));
    callbacks.add(
        new dev.mikoto2000.rei.event.ToolEventCallbackDecorator(local, factory, events::add));
    var prompt =
        new Prompt(
            "review and fix",
            OpenAiChatOptions.builder()
                .toolCallbacks(callbacks)
                .toolContext(Map.of("toolCallId", "test-call"))
                .build());
    var response =
        new ChatResponse(
            List.of(
                new org.springframework.ai.chat.model.Generation(
                    org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    "search",
                                    "function",
                                    searchTool,
                                    searchTool.equals("webSearch")
                                        ? "{\"query\":\"query\",\"limit\":1}"
                                        : "{\"request\":{\"query\":\"query\",\"maxResults\":1,\"readTop\":0}}"),
                                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    "fix", "function", "applyTextDiff", "{}")))
                        .build())));
    var result = new ToolLoopSupport().execute(prompt, response);
    var feedback =
        (org.springframework.ai.chat.messages.ToolResponseMessage)
            result.conversationHistory().getLast();
    assertThat(feedback.getResponses()).hasSize(2);
    var failure =
        new tools.jackson.databind.json.JsonMapper()
            .readTree(feedback.getResponses().getFirst().responseData());
    assertThat(failure.path("success").asBoolean()).isFalse();
    assertThat(failure.path("httpStatus").asInt()).isEqualTo(402);
    assertThat(failure.path("error").asString()).isEqualTo("payment_required");
    assertThat(fixed).isTrue();
    assertThat(feedback.getResponses().getLast().responseData()).isEqualTo("fixed");
    assertThat(events.stream().map(dev.mikoto2000.rei.event.AgentEvent::type).toList())
        .containsExactly(
            dev.mikoto2000.rei.event.AgentEventType.TOOL_STARTED,
            dev.mikoto2000.rei.event.AgentEventType.TOOL_FAILED,
            dev.mikoto2000.rei.event.AgentEventType.TOOL_STARTED,
            dev.mikoto2000.rei.event.AgentEventType.TOOL_COMPLETED);
  }

  /** 会話の実行の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void missingFileDoesNotAbortRemainingToolCalls(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws java.io.IOException {
    java.nio.file.Files.writeString(root.resolve("present.md"), "hello");
    var tools = new SampleFileTools(root);
    var events = new java.util.ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var provider =
        org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build();
    var callback =
        new dev.mikoto2000.rei.event.ToolEventCallbackProvider(
                provider,
                new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
                events::add)
            .getToolCallbacks()[0];
    var prompt =
        new Prompt("review all files", OpenAiChatOptions.builder().toolCallbacks(callback).build());
    var response =
        new ChatResponse(
            List.of(
                new org.springframework.ai.chat.model.Generation(
                    org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    "missing",
                                    "function",
                                    "readBinaryFile",
                                    "{\"pathStr\":\"missing.md\"}"),
                                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    "present",
                                    "function",
                                    "readBinaryFile",
                                    "{\"pathStr\":\"present.md\"}")))
                        .build())));

    var result = new ToolLoopSupport().execute(prompt, response);
    var feedback =
        (org.springframework.ai.chat.messages.ToolResponseMessage)
            result.conversationHistory().getLast();
    assertThat(feedback.getResponses()).hasSize(2);
    assertThat(feedback.getResponses().getFirst().responseData()).contains("source_missing");
    assertThat(
            dev.mikoto2000.rei.core.stagnation.ProgressEvaluator.failedResult(
                feedback.getResponses().getLast().responseData()))
        .isFalse();
    assertThat(tools.attempted).containsExactly("missing.md", "present.md");
    assertThat(events.stream().map(dev.mikoto2000.rei.event.AgentEvent::type).toList())
        .containsExactly(
            dev.mikoto2000.rei.event.AgentEventType.TOOL_STARTED,
            dev.mikoto2000.rei.event.AgentEventType.TOOL_FAILED,
            dev.mikoto2000.rei.event.AgentEventType.TOOL_STARTED,
            dev.mikoto2000.rei.event.AgentEventType.TOOL_COMPLETED);
  }

  /** 検証条件を再現するためのサンプルファイルツール一覧。 */
  public static class SampleFileTools {
    /** 検証に使う作業ルート。 */
    private final java.nio.file.Path root;

    /** 検証に使うattempted。 */
    final java.util.List<String> attempted = new java.util.ArrayList<>();

    /** 検証条件に必要な値を受け取り、SampleFileToolsを用意する。 */
    SampleFileTools(java.nio.file.Path root) {
      this.root = root;
    }

    /** 検証用に、指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
    @org.springframework.ai.tool.annotation.Tool(
        name = "readBinaryFile",
        description = "read test file")
    public byte[] read(String pathStr) throws java.io.IOException {
      attempted.add(pathStr);
      return java.nio.file.Files.readAllBytes(root.resolve(pathStr));
    }
  }

  /** 既定完了時の処理一覧を受け付けないことを確認する。 */
  @Test
  void rejectsDefaultCallbacks() {
    ToolCallback callback =
        new ToolCallback() {
          /** 検証用に、会話の実行で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("ambient")
                .description("ambient")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            throw new AssertionError("must not execute");
          }
        };
    assertThatThrownBy(() ->
            ToolLoopSupport.requireNoDefaultTools(
                model(OpenAiChatOptions.builder().toolCallbacks(callback).build())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 加工前のツール一覧を受け付けないことを確認する。 */
  @Test
  void rejectsRawToolsInOfficialSdkExtraBody() {
    var options =
        OpenAiChatOptions.builder()
            .extraBody(
                Map.of(
                    "tools",
                    List.of(Map.of("type", "function", "function", Map.of("name", "ambient")))))
            .build();
    assertThatThrownBy(() -> ToolLoopSupport.requireNoDefaultTools(model(options)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 存在しないを受け付けられることを確認する。 */
  @Test
  void acceptsAbsentAndEmptyToolCollections() {
    assertThatCode(
            () -> ToolLoopSupport.requireNoDefaultTools(model(OpenAiChatOptions.builder().build())))
        .doesNotThrowAnyException();
    assertThatCode(() ->
            ToolLoopSupport.requireNoDefaultTools(
                model(
                    OpenAiChatOptions.builder()
                        .toolCallbacks(List.of())
                        .extraBody(Map.of("tools", List.of(), "custom", "value"))
                        .build())))
        .doesNotThrowAnyException();
  }

  /** 会話の実行で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void existingRunBudgetOwnsPerToolLimit() {
    executeAfterPriorResponses(40, 1);
  }

  /** 会話の実行で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void existingRunBudgetOwnsTotalToolLimit() {
    executeAfterPriorResponses(150, 5);
  }

  /** 検証用に、依頼された操作を進めるため、変更後Prior応答一覧を実行する。 */
  private void executeAfterPriorResponses(int priorCalls, int names) {
    var invocations = new java.util.concurrent.atomic.AtomicInteger();
    ToolCallback callback =
        new ToolCallback() {
          /** 検証用に、会話の実行で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("tool0")
                .description("test")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            invocations.incrementAndGet();
            return "done";
          }
        };
    var messages = new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
    messages.add(new org.springframework.ai.chat.messages.UserMessage("bounded application run"));
    for (int i = 0; i < priorCalls; i++) {
      messages.add(
          org.springframework.ai.chat.messages.ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                          "old-" + i, "tool" + (i % names), "ok")))
              .build());
    }
    var prompt = new Prompt(messages, OpenAiChatOptions.builder().toolCallbacks(callback).build());
    var response =
        new ChatResponse(
            List.of(
                new org.springframework.ai.chat.model.Generation(
                    org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                    "new", "function", "tool0", "{}")))
                        .build())));
    new ToolLoopSupport().execute(prompt, response);
    assertThat(invocations.get()).isEqualTo(1);
  }

  /** 検証で使用するモデルを、指定された条件に合わせて用意する。 */
  private ChatModel model(ChatOptions options) {
    return new ChatModel() {
      /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
      public ChatOptions getOptions() {
        return options;
      }

      /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
      public ChatResponse call(Prompt prompt) {
        throw new AssertionError("must not call model");
      }
    };
  }

  @Test
  void pendingToolsRejectMissingExplicitCallbacksBeforeExecution() {
    var pending = new RunContinuation.PendingTools("", List.of(), List.of());
    var context =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.class);
    assertThatThrownBy(
            () -> new ToolLoopSupport().executePending(new Prompt("resume"), pending, context))
        .isInstanceOf(IllegalArgumentException.class);
    org.mockito.Mockito.verifyNoInteractions(context);
  }

  @Test
  void pendingToolsPreserveTheOriginalCallbackFailure() {
    var failure = new IllegalStateException("callback failed");
    var callback = org.mockito.Mockito.mock(ToolCallback.class);
    org.mockito.Mockito.when(callback.getToolDefinition())
        .thenReturn(
            ToolDefinition.builder()
                .name("fail")
                .description("failure fixture")
                .inputSchema("{}")
                .build());
    org.mockito.Mockito.when(
            callback.call(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(failure);
    var prompt = new Prompt("resume", OpenAiChatOptions.builder().toolCallbacks(callback).build());
    var pending =
        new RunContinuation.PendingTools(
            "",
            List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                    "id", "function", "fail", "{}")),
            List.of());
    var context =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.class);
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> new ToolLoopSupport().executePending(prompt, pending, context)))
        .isSameAs(failure);
  }
}
