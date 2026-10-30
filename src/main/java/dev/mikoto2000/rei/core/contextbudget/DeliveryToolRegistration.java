package dev.mikoto2000.rei.core.contextbudget;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * モデルへ渡す文脈の管理に必要な配信ツールRegistrationを扱う。
 *
 * <p>Policies originate only in annotations on the objects supplied by application registration.
 */
public final class DeliveryToolRegistration {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private DeliveryToolRegistration() {}

  /** モデルへ渡す文脈の管理に必要な登録済みを扱う。 */
  public interface Registered extends ToolCallback {
    /** モデルへ渡す文脈の管理で用いる配信種類を返す。 */
    ResultDelivery.Kind deliveryKind();

    /** モデルへ渡す文脈の管理において、レビューページに該当するか判定する。 */
    default boolean reviewPage() {
      return false;
    }
  }

  /** モデルへ渡す文脈の管理で用いる提供元を返す。 */
  public static ToolCallbackProvider provider(Object... objects) {
    Map<String, ResultDelivery.Kind> policies = new HashMap<>();
    Set<String> reviewPages = new HashSet<>();
    for (Object object : objects)
      for (var method : object.getClass().getDeclaredMethods()) {
        var delivery = method.getAnnotation(ResultDelivery.class);
        var tool = method.getAnnotation(Tool.class);
        if (delivery != null && tool != null) {
          String name = tool.name().isBlank() ? method.getName() : tool.name();
          if (method.getReturnType() == dev.mikoto2000.rei.review.ReviewTools.Page.class
              || delivery.reviewPages()) reviewPages.add(name);
          if (policies.putIfAbsent(name, delivery.value()) != null)
            throw new IllegalArgumentException("Duplicate delivery tool registration: " + name);
        }
      }
    var callbacks =
        MethodToolCallbackProvider.builder().toolObjects(objects).build().getToolCallbacks();
    return () ->
        Arrays.stream(callbacks)
            .map(
                callback ->
                    wrap(
                        callback,
                        policies.getOrDefault(
                            callback.getToolDefinition().name(), ResultDelivery.Kind.GENERAL),
                        reviewPages.contains(callback.getToolDefinition().name())))
            .toArray(ToolCallback[]::new);
  }

  /** モデルへ渡す文脈の管理で用いる種類を返す。 */
  public static ResultDelivery.Kind kind(ToolCallback callback) {
    return callback instanceof Registered registered
        ? registered.deliveryKind()
        : ResultDelivery.Kind.GENERAL;
  }

  /** モデルへ渡す文脈の管理で用いるpreserve判断基準を返す。 */
  public static ToolCallback preservePolicy(ToolCallback source, ToolCallback decorated) {
    return wrap(
        decorated,
        kind(source),
        source instanceof Registered registered && registered.reviewPage());
  }

  /** モデルへ渡す文脈の管理で用いる共通形式への調整を返す。 */
  private static ToolCallback wrap(
      ToolCallback delegate, ResultDelivery.Kind kind, boolean reviewPage) {
    return new Registered() {
      /** モデルへ渡す文脈の管理で用いる配信種類を返す。 */
      public ResultDelivery.Kind deliveryKind() {
        return kind;
      }

      /** モデルへ渡す文脈の管理において、レビューページに該当するか判定する。 */
      public boolean reviewPage() {
        return reviewPage;
      }

      /** モデルへ渡す文脈の管理で参照するツール定義を返す。 */
      public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
      }

      /** モデルへ渡す文脈の管理で参照するツール付随情報を返す。 */
      public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
      }

      /** モデルへ渡す文脈の管理の要求を実行し、呼び出し元へ結果を返す。 */
      public String call(String input) {
        return delegate.call(input);
      }

      /** モデルへ渡す文脈の管理の要求を実行し、呼び出し元へ結果を返す。 */
      public String call(String input, ToolContext context) {
        return delegate.call(input, context);
      }
    };
  }
}
