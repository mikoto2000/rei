package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.ResultDelivery;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 補助エージェントへの委譲に必要な必要時に取得する委譲完了時の処理を扱う。
 *
 * <p>Keeps default ChatClient construction independent of the application runner dependency graph.
 */
public final class LazyDelegationCallback implements DeliveryToolRegistration.Registered {
  /** ツール一覧。 */
  private final ObjectProvider<SubAgentTools> tools;

  /** 名前。 */
  private final String name;

  /** 補助エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public LazyDelegationCallback(ObjectProvider<SubAgentTools> tools) {
    this(tools, "delegateTask");
  }

  /** 指定された値や利用先を使って、LazyDelegationCallbackを初期化する。 */
  public LazyDelegationCallback(ObjectProvider<SubAgentTools> tools, String name) {
    this.tools = tools;
    this.name = name;
  }

  /** 補助エージェントへの委譲で参照するツール定義を返す。 */
  public ToolDefinition getToolDefinition() {
    return tools.getObject().callback(name).getToolDefinition();
  }

  /** 補助エージェントへの委譲で参照するツール付随情報を返す。 */
  public ToolMetadata getToolMetadata() {
    return tools.getObject().callback(name).getToolMetadata();
  }

  /** 補助エージェントへの委譲で用いる配信種類を返す。 */
  public ResultDelivery.Kind deliveryKind() {
    return DeliveryToolRegistration.kind(tools.getObject().callback(name));
  }

  /** 補助エージェントへの委譲において、レビューページに該当するか判定する。 */
  public boolean reviewPage() {
    return tools.getObject().callback(name)
            instanceof DeliveryToolRegistration.Registered registered
        && registered.reviewPage();
  }

  /** 補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
  public String call(String input) {
    return tools.getObject().callback(name).call(input);
  }

  /** 補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
  public String call(String input, ToolContext context) {
    return tools.getObject().callback(name).call(input, context);
  }
}
