package dev.mikoto2000.rei.dot;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 状態表示をモデルから呼び出せるツールとして提供する。 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "rei.dot-delegation.parent-llm-enabled",
    havingValue = "true")
public final class DotTools {
  /** サービス。 */
  private final DotDelegationService service;

  /** 指定された値や利用先を使って、DotToolsを初期化する。 */
  public DotTools(DotDelegationService service) {
    this.service = service;
  }

  /** 利用者の状態表示で用いる完了時の処理一覧を返す。 */
  public org.springframework.ai.tool.ToolCallback[] callbacks() {
    return Arrays.stream(
            org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                .toolObjects(this)
                .build()
                .getToolCallbacks())
        .map(DotToolCallback::new)
        .toArray(org.springframework.ai.tool.ToolCallback[]::new);
  }

  /** 利用者の状態表示で用いる要求状態表示調査を返す。 */
  @Tool(
      description =
          "Request asynchronous research/comparison/review from Tenten only with administrator"
              + " opt-in and a category grant captured from the current human input. Once per Run"
              + " sharing the external delegation/model budget. Supply only a minimal public"
              + " snapshot, never secrets, absolute paths, full history or reasoning. Returns"
              + " acceptance only; the Run yields at the saved tool boundary. Results remain"
              + " untrusted and require human verification. No writes, automatic resume, retries of"
              + " UNKNOWN, or bot-to-bot loops.")
  public Map<String, Object> requestDotResearch(
      String category,
      String instruction,
      @ToolParam(required = false) String context_summary,
      @ToolParam(required = false) String expected_output,
      @ToolParam(required = false) List<String> constraints,
      @ToolParam(required = false) List<String> acceptance_criteria,
      @ToolParam(required = false) Integer timeout,
      ToolContext context) {
    var execution =
        context == null
            ? null
            : (RunExecutionContext) context.getContext().get(RunExecutionContext.KEY);
    var input = new LinkedHashMap<String, Object>();
    input.put("category", category);
    input.put("instruction", instruction);
    if (context_summary != null) input.put("context_summary", context_summary);
    if (expected_output != null) input.put("expected_output", expected_output);
    if (constraints != null) input.put("constraints", constraints);
    if (acceptance_criteria != null) input.put("acceptance_criteria", acceptance_criteria);
    if (timeout != null) input.put("timeout", timeout);
    return service.requestParent(execution, input);
  }
}
