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
    String result = read.call("{\"request\":{\"url\":\"http://127.0.0.1:1/api\"}}", new org.springframework.ai.chat.model.ToolContext(java.util.Map.of("fixture", true)));
    assertTrue(result.contains("URL_NOT_ALLOWED"));
  }

  @Test
  void springToolArgumentsPreserveStructuredBodiesAndJsonNull() throws Exception {
    var service = org.mockito.Mockito.mock(HttpApiService.class);
    var callbacks = MethodToolCallbackProvider.builder().toolObjects(new HttpApiTools(service)).build().getToolCallbacks();
    var read = java.util.Arrays.stream(callbacks)
        .filter(callback -> callback.getToolDefinition().name().equals("httpRequestRead"))
        .findFirst().orElseThrow();
    for (String body : java.util.List.of("{\"title\":\"hello\"}", "[1,2]", "null")) {
      read.call("{\"request\":{\"url\":\"https://example.com\",\"body\":" + body + "}}",
          new org.springframework.ai.chat.model.ToolContext(java.util.Map.of("fixture", true)));
      var argument = org.mockito.ArgumentCaptor.forClass(HttpApiRequest.class);
      org.mockito.Mockito.verify(service).read(argument.capture());
      assertEquals(body, argument.getValue().body().toString());
      org.mockito.Mockito.clearInvocations(service);
    }
  }
}
