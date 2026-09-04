package dev.mikoto2000.rei.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

class ComputerUseToolsTest {

  @Test
  void exposesOnlyObserveAndActWorkflowTools() {
    ComputerUseTools tools = new ComputerUseTools(mock(ComputerUseService.class));

    List<String> names = Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build()
        .getToolCallbacks())
        .map(callback -> callback.getToolDefinition().name())
        .sorted()
        .toList();

    assertEquals(List.of("computerAct", "computerObserve"), names);
  }

  @Test
  void observeAndActDelegateToService() {
    ComputerUseService service = mock(ComputerUseService.class);
    ComputerUseTools tools = new ComputerUseTools(service);
    ComputerObservationRequest observeRequest = new ComputerObservationRequest(1, 5, true, true);
    ComputerObservation observation = new ComputerObservation("obs", Optional.empty(), List.of(), List.of());
    ComputerActionRequest actionRequest = ComputerActionRequest.invoke("obs", "e1");
    ComputerActionResult actionResult = ComputerActionResult.success(ComputerActionBackend.UI_AUTOMATION, false);
    when(service.observe(observeRequest)).thenReturn(observation);
    when(service.act(actionRequest)).thenReturn(actionResult);

    assertEquals(observation, tools.computerObserve(observeRequest));
    assertEquals(actionResult, tools.computerAct(actionRequest));
    verify(service).observe(observeRequest);
    verify(service).act(actionRequest);
  }
}
