package dev.mikoto2000.rei.slack;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Slackとの連携に必要なSlackエージェントツール完了時の処理を扱う。
 *
 * <p>Trusted registration authorizes the resolved destination inside askExternalAgent, exactly
 * once.
 */
public final class SlackAgentToolCallback implements ToolCallback {
  /** 委譲先。 */
  private final ToolCallback delegate;

  /** 指定された値や利用先を使って、SlackAgentToolCallbackを初期化する。 */
  SlackAgentToolCallback(ToolCallback delegate) {
    this.delegate = delegate;
  }

  /** Slackとの連携において、authorizes仕様に該当するか判定する。 */
  public boolean authorizesSpecification() {
    return "askExternalAgent".equals(getToolDefinition().name());
  }

  /** Slackとの連携で参照するツール定義を返す。 */
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  /** Slackとの連携で参照するツール付随情報を返す。 */
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  /** Slackとの連携の要求を実行し、呼び出し元へ結果を返す。 */
  public String call(String input) {
    return delegate.call(input);
  }

  /** Slackとの連携の要求を実行し、呼び出し元へ結果を返す。 */
  public String call(String input, ToolContext context) {
    return delegate.call(input, context);
  }
}
