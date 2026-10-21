package dev.mikoto2000.rei.externalagent;

import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 外部エージェントへの委譲に必要な外部エージェントツール完了時の処理を扱う。
 *
 * <p>Trusted application registration, never a model/MCP-provided tool name.
 */
public final class ExternalAgentToolCallback implements ToolCallback {
  /** 委譲先。 */
  private final ToolCallback delegate;

  /** 指定された値や利用先を使って、ExternalAgentToolCallbackを初期化する。 */
  ExternalAgentToolCallback(ToolCallback delegate) {
    this.delegate = delegate;
  }

  /** 外部エージェントへの委譲において、authorizes仕様に該当するか判定する。 */
  public boolean authorizesSpecification() {
    return Set.of("prepareCodexImplementation", "requestCodexImplementation")
        .contains(getToolDefinition().name());
  }

  /** 外部エージェントへの委譲において、利用可能変更後委譲に該当するか判定する。 */
  public boolean availableAfterDelegation() {
    return Set.of(
            "requestCodexImplementation",
            "getCodexImplementationRequest",
            "getExternalImplementation",
            "inspectExternalImplementation",
            "recordCodexAcceptanceEvaluation")
        .contains(getToolDefinition().name());
  }

  /** 外部エージェントへの委譲で参照するツール定義を返す。 */
  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  /** 外部エージェントへの委譲で参照するツール付随情報を返す。 */
  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  /** 外部エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
  @Override
  public String call(String input) {
    return delegate.call(input);
  }

  /** 外部エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
  @Override
  public String call(String input, ToolContext context) {
    return delegate.call(input, context);
  }
}
