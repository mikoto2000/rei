package dev.mikoto2000.rei.core.contextbudget;

import java.util.*;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;

/** Policies originate only in annotations on the objects supplied by application registration. */
public final class DeliveryToolRegistration {
  private DeliveryToolRegistration() { }
  public interface Registered extends ToolCallback { ResultDelivery.Kind deliveryKind(); }
  public static ToolCallbackProvider provider(Object... objects) {
    Map<String, ResultDelivery.Kind> policies = new HashMap<>();
    for (Object object : objects) for (var method : object.getClass().getDeclaredMethods()) {
      var delivery = method.getAnnotation(ResultDelivery.class);
      var tool = method.getAnnotation(Tool.class);
      if (delivery != null && tool != null) {
        String name = tool.name().isBlank() ? method.getName() : tool.name();
        if (policies.putIfAbsent(name, delivery.value()) != null)
          throw new IllegalArgumentException("Duplicate delivery tool registration: " + name);
      }
    }
    var callbacks = MethodToolCallbackProvider.builder().toolObjects(objects).build().getToolCallbacks();
    return () -> Arrays.stream(callbacks).map(callback -> wrap(callback,
        policies.getOrDefault(callback.getToolDefinition().name(), ResultDelivery.Kind.GENERAL))).toArray(ToolCallback[]::new);
  }
  public static ResultDelivery.Kind kind(ToolCallback callback) {
    return callback instanceof Registered registered ? registered.deliveryKind() : ResultDelivery.Kind.GENERAL;
  }
  public static ToolCallback preservePolicy(ToolCallback source, ToolCallback decorated) {
    return wrap(decorated, kind(source));
  }
  private static ToolCallback wrap(ToolCallback delegate, ResultDelivery.Kind kind) {
    return new Registered() {
      public ResultDelivery.Kind deliveryKind() { return kind; }
      public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
      public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
      public String call(String input) { return delegate.call(input); }
      public String call(String input, ToolContext context) { return delegate.call(input, context); }
    };
  }
}
