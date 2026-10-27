package dev.mikoto2000.rei.httpapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** The model sees a structured read tool with the intrinsic read capability. */
class HttpApiRegistrationTest {
  @Test
  void publishesReadSchemaAndClassification() {
    var callbacks = MethodToolCallbackProvider.builder()
        .toolObjects(new HttpApiTools(new HttpApiService(new dev.mikoto2000.rei.http.SafeHttpFetcher(),
            new HttpApiProperties(null, null, null, null, null))))
        .build().getToolCallbacks();
    var read = java.util.Arrays.stream(callbacks)
        .filter(callback -> callback.getToolDefinition().name().equals("httpRequestRead"))
        .findFirst().orElseThrow();
    assertTrue(read.getToolDefinition().inputSchema().contains("jsonPointer"));
    assertEquals(Set.of(ActionCapability.NETWORK_READ), ToolPermissionPolicy.intrinsicCapabilities("httpRequestRead"));
  }
}
