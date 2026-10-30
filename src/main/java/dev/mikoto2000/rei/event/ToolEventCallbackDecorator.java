package dev.mikoto2000.rei.event;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ToolCallback の実行前後で Agent Event を発行する Decorator。
 *
 * <p>各 Tool 実装に個別追加するのではなく、共通実行境界である {@code call} をラップして tool.started / tool.completed /
 * tool.failed を発行する。 結果全文は payload に格納せず、要約情報のみを保持する。
 */
public class ToolEventCallbackDecorator
    implements dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.Registered {

  // Only requested filesystem operations may recover. A missing internal resource in
  // another tool (for example checkpoint storage) must still fail the Run.
  /** ファイルツール一覧。 */
  private static final java.util.Set<String> FILE_TOOLS =
      java.util.Set.of(
          "readMultiFile",
          "readFile",
          "readTextFile",
          "readTextFileRange",
          "readPdfFile",
          "readBinaryFile",
          "countFileLines",
          "findFile",
          "listFile",
          "grepMultiQuery",
          "searchAndRead",
          "searchFilePage",
          "readSkillResource",
          "writeMultiFile",
          "writeTextFile",
          "writeBinaryFile",
          "applyTextDiff",
          "deleteFile",
          "copyFile",
          "moveFile",
          "createDirectories");

  /** 委譲先。 */
  private final ToolCallback delegate;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 指定された値や利用先を使って、ToolEventCallbackDecoratorを初期化する。 */
  public ToolEventCallbackDecorator(
      ToolCallback delegate, AgentEventFactory eventFactory, AgentEventPublisher eventPublisher) {
    this.delegate = delegate;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  /** 実行状況の通知において、レビューページに該当するか判定する。 */
  @Override
  public boolean reviewPage() {
    return delegate
            instanceof
            dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.Registered registered
        && registered.reviewPage();
  }

  /** 実行状況の通知で用いる配信種類を返す。 */
  @Override
  public dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind deliveryKind() {
    return dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.kind(delegate);
  }

  /** 実行状況の通知で参照するツール定義を返す。 */
  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  /** 実行状況の通知の要求を実行し、呼び出し元へ結果を返す。 */
  @Override
  public String call(String toolInput) {
    return emitAndCall(toolInput, null, () -> delegate.call(toolInput));
  }

  /** 実行状況の通知の要求を実行し、呼び出し元へ結果を返す。 */
  @Override
  public String call(String toolInput, ToolContext toolContext) {
    return emitAndCall(toolInput, toolContext, () -> delegate.call(toolInput, toolContext));
  }

  /** 実行状況の通知で用いる通知および呼び出しを返す。 */
  private String emitAndCall(String toolInput, ToolContext toolContext, Supplier<String> caller) {
    var owner = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    if (toolContext != null
        && toolContext
                .getContext()
                .get(dev.mikoto2000.rei.core.chat.AgentRunContext.class.getName())
            instanceof dev.mikoto2000.rei.core.chat.AgentRunContext captured) owner = captured;
    var modelBudget = dev.mikoto2000.rei.llm.ModelCallBudgetScope.current();
    if (toolContext != null
        && toolContext.getContext().get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
            instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution)
      modelBudget = execution.modelCallBudget();
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner);
        var budgetScope = dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(modelBudget)) {
      String toolName = delegate.getToolDefinition().name();
      String toolCallId = resolveToolCallId(toolContext);
      long startedAtNanos = System.nanoTime();

      eventPublisher.publishBoundary(
          eventFactory.toolStarted(toolCallId, toolName, summarize(toolInput)));
      String result;
      try {
        // Cancellation can arrive while the durable STARTED boundary is being written.
        if (Thread.currentThread().isInterrupted())
          throw new java.util.concurrent.CancellationException();
        if (toolContext != null
            && toolContext
                    .getContext()
                    .get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
                instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution)
          execution.checkActive();
        result = caller.get();
      } catch (RuntimeException e) {
        String searchFailure = searchFailureResult(toolName, e);
        String structuredFailure =
            !Thread.currentThread().isInterrupted()
                    && !dev.mikoto2000.rei.core.stagnation.RunExecutionContext.isControlFailure(e)
                ? dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.toolFailureResult(e)
                : null;
        eventPublisher.publishBoundary(
            eventFactory.toolFailed(
                toolCallId,
                toolName,
                searchFailure != null || structuredFailure != null
                    ? dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.safeError(e)
                    : new ErrorInformation(
                        e.getClass().getSimpleName(), summarize(e.getMessage()), null)));
        if (searchFailure != null) return searchFailure;
        if (structuredFailure != null) return structuredFailure;
        if (FILE_TOOLS.contains(toolName) && !Thread.currentThread().isInterrupted()) {
          var diagnostic = dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(e);
          if (diagnostic.code().equals("source_missing")
              || diagnostic.code().equals("file_unavailable")) {
            // Return an explicit failure to the model, never a successful/empty read.
            // Keep TOOL_FAILED above; do not emit TOOL_COMPLETED for this invocation.
            return """
              {"success":false,"error":"%s","message":"%s","guidance":"findFile または listFile で実際のパスを確認してください。同じ存在しないパスへの再試行を繰り返さず、見つからない場合は未確認として記録し、他の対象の作業を続けてください。操作が成功したとは扱わないでください。"}
              """
                .formatted(diagnostic.code(), diagnostic.message());
          }
        }
        throw e;
      }
      long duration = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
      // Storage failure after the side effect must leave STARTED, never falsely record FAILED.
      eventPublisher.publishBoundary(
          eventFactory.toolCompleted(toolCallId, toolName, duration, summarize(result)));
      return result;
    }
  }

  /** 検索先の障害は失敗結果として返し、独立したローカル作業を続行できるようにする。 */
  private String searchFailureResult(String toolName, RuntimeException error) {
    if (java.util.Set.of("searchAndRead", "grepMultiQuery", "searchFilePage").contains(toolName)
        && !Thread.currentThread().isInterrupted()
        && !dev.mikoto2000.rei.core.stagnation.RunExecutionContext.isControlFailure(error)
        && dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error)
            .code()
            .equals(dev.mikoto2000.rei.toolcontract.FileResultBudgetExceededException.CODE)) {
      return """
          {"success":false,"error":"file_result_budget_exceeded","message":"%s","guidance":"検索結果は取得できていません。同じ条件を繰り返さず、baseDir・includeGlob・検索語を絞り、maxFiles・contextLinesを減らして再試行してください。既知のパスはreadMultiFileで確認できます。"}
          """
          .formatted(dev.mikoto2000.rei.toolcontract.FileResultBudgetExceededException.MESSAGE);
    }
    if (!java.util.Set.of("webSearch", "webSearchAndRead").contains(toolName)
        || Thread.currentThread().isInterrupted()
        || dev.mikoto2000.rei.core.stagnation.RunExecutionContext.isControlFailure(error))
      return null;
    Integer status = null;
    boolean recoverable = false;
    Throwable cause = error;
    for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
      if (cause instanceof dev.mikoto2000.rei.toolcontract.ExternalReadFailure external) {
        status = external.httpStatus();
        recoverable = true;
      }
      if (cause instanceof java.io.IOException) recoverable = true;
    }
    if (!recoverable) return null;
    var diagnostic = dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error);
    return """
        {"success":false,"error":"%s","httpStatus":%s,"message":"%s","guidance":"検索結果は取得できていません。同じ検索の再試行を繰り返さず、既知の公式URLを取得するか、外部確認が不要なローカルのレビュー・修正を続けてください。外部確認が必要な項目は未確認として記録し、推測で確定しないでください。"}
        """
        .formatted(diagnostic.code(), status, diagnostic.message());
  }

  /** ToolContext から toolCallId を取得する。無ければ UUID を生成する。 */
  private String resolveToolCallId(ToolContext toolContext) {
    if (toolContext != null && toolContext.getContext() != null) {
      Object id = toolContext.getContext().get("toolCallId");
      if (id != null) {
        return id.toString();
      }
    }
    return UUID.randomUUID().toString();
  }

  /** 引数・結果の要約。全文を payload に入れないための summary を返す。 */
  private String summarize(String value) {
    if (value == null) {
      return "";
    }
    String sanitized = CredentialRedactor.redact(value);
    int maxLength = 120;
    if (sanitized.length() <= maxLength) {
      return sanitized;
    }
    return sanitized.substring(0, maxLength) + "... (" + sanitized.length() + " chars)";
  }
}
