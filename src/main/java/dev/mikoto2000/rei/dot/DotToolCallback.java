package dev.mikoto2000.rei.dot;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 利用者の状態表示に必要な状態表示ツール完了時の処理を扱う。
 *
 * <p>Trusted registration authorizes the resolved public snapshot exactly once.
 */
public final class DotToolCallback implements ToolCallback {
  /** 委譲先。 */
  private final ToolCallback delegate;

  /** 指定された値や利用先を使って、DotToolCallbackを初期化する。 */
  DotToolCallback(ToolCallback delegate) {
    this.delegate = delegate;
  }

  /** 利用者の状態表示において、authorizes仕様に該当するか判定する。 */
  public boolean authorizesSpecification() {
    return getToolDefinition().name().equals("requestDotResearch");
  }

  /** 利用者の状態表示で参照するツール定義を返す。 */
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  /** 利用者の状態表示で参照するツール付随情報を返す。 */
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  /** 利用者の状態表示の要求を実行し、呼び出し元へ結果を返す。 */
  public String call(String input) {
    return delegate.call(input);
  }

  /** 利用者の状態表示の要求を実行し、呼び出し元へ結果を返す。 */
  public String call(String input, ToolContext context) {
    return delegate.call(input, context);
  }
}
