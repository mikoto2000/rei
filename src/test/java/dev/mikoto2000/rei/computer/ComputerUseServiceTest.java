package dev.mikoto2000.rei.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ComputerUseServiceTest {

  @Test
  void observeAssignsShortElementIdsPerObservation() {
    FakeUiAutomationBackend ui = new FakeUiAutomationBackend();
    ui.windows = List.of(new ComputerWindow(null, "Calculator", "CalcFrame", new ComputerBounds(0, 0, 300, 200),
        true, true));
    ui.elements = List.of(element(null, "button", "One", EnumSet.of(ComputerCapability.INVOKE)),
        element(null, "edit", "Display", EnumSet.of(ComputerCapability.SET_VALUE)));
    ComputerUseService service = new ComputerUseService(ui, new FakePhysicalInputBackend(),
        new ComputerUseProperties());

    ComputerObservation observation = service.observe(new ComputerObservationRequest(2, 10, true, true));

    assertEquals("e1", observation.elements().get(0).id());
    assertEquals("e2", observation.elements().get(1).id());
    assertEquals("Calculator", observation.activeWindow().orElseThrow().title());
    assertTrue(observation.windows().get(0).active());
  }

  @Test
  void invokeUsesSemanticBackendWhenCapabilityExists() {
    FakeUiAutomationBackend ui = new FakeUiAutomationBackend();
    ui.elements = List.of(element(null, "button", "Save", EnumSet.of(ComputerCapability.INVOKE)));
    ComputerUseService service = new ComputerUseService(ui, new FakePhysicalInputBackend(),
        new ComputerUseProperties());
    ComputerObservation observation = service.observe(null);

    ComputerActionResult result = service.act(ComputerActionRequest.invoke(observation.observationId(), "e1"));

    assertTrue(result.success());
    assertEquals(ComputerActionBackend.UI_AUTOMATION, result.backend());
    assertFalse(result.fallbackUsed());
    assertEquals(List.of("invoke:e1"), ui.actions);
  }

  @Test
  void invokeFallsBackToRobotClickWhenSemanticInvokeUnsupportedButElementIsSafe() {
    FakeUiAutomationBackend ui = new FakeUiAutomationBackend();
    ComputerElement button = element(null, "button", "Custom", EnumSet.of(ComputerCapability.PHYSICAL_CLICK));
    ui.elements = List.of(button);
    FakePhysicalInputBackend physical = new FakePhysicalInputBackend();
    ComputerUseService service = new ComputerUseService(ui, physical, new ComputerUseProperties());
    ComputerObservation observation = service.observe(null);

    ComputerActionResult result = service.act(ComputerActionRequest.invoke(observation.observationId(), "e1"));

    assertTrue(result.success());
    assertTrue(result.fallbackUsed());
    assertEquals(ComputerActionBackend.ROBOT, result.backend());
    assertEquals(List.of(new Point(15, 15)), physical.clicks);
  }

  @Test
  void fallbackRejectsDisabledOffscreenInvalidOrStaleElements() {
    FakeUiAutomationBackend ui = new FakeUiAutomationBackend();
    ui.elements = List.of(new ComputerElement(null, "button", "Disabled", null, null, null,
        new ComputerBounds(0, 0, 10, 10), false, false, false, EnumSet.of(ComputerCapability.PHYSICAL_CLICK)));
    FakePhysicalInputBackend physical = new FakePhysicalInputBackend();
    ComputerUseService service = new ComputerUseService(ui, physical, new ComputerUseProperties());
    ComputerObservation observation = service.observe(null);

    ComputerActionResult disabled = service.act(ComputerActionRequest.invoke(observation.observationId(), "e1"));
    ComputerActionResult stale = service.act(ComputerActionRequest.invoke("old", "e1"));

    assertFalse(disabled.success());
    assertTrue(disabled.failureReason().orElseThrow().contains("disabled"));
    assertFalse(stale.success());
    assertTrue(stale.failureReason().orElseThrow().contains("stale"));
    assertTrue(physical.clicks.isEmpty());
  }

  @Test
  void loopStopsAtActionLimitAndRepeatedAction() {
    FakeUiAutomationBackend ui = new FakeUiAutomationBackend();
    ui.elements = List.of(element(null, "button", "Next", EnumSet.of(ComputerCapability.INVOKE)));
    ComputerUseService service = new ComputerUseService(ui, new FakePhysicalInputBackend(),
        new ComputerUseProperties());
    ComputerUseLoop loop = new ComputerUseLoop(service, new ComputerUseProperties());

    ComputerUseLoopResult result = loop.run("press next", state -> ComputerActionRequest.invoke(
        state.observationId(), "e1"), () -> false);

    assertFalse(result.completed());
    assertTrue(result.stopReason().contains("repeated"));
  }

  private static ComputerElement element(String id, String role, String name, EnumSet<ComputerCapability> capabilities) {
    return new ComputerElement(id, role, name, null, null, null, new ComputerBounds(10, 10, 10, 10), true, false,
        false, capabilities);
  }

  private static class FakeUiAutomationBackend implements UiAutomationBackend {
    List<ComputerWindow> windows = List.of();
    List<ComputerElement> elements = List.of();
    List<String> actions = new ArrayList<>();

    @Override
    public ComputerObservation observe(ComputerObservationRequest request) {
      return new ComputerObservation("raw", windows.stream().filter(ComputerWindow::active).findFirst(), windows,
          elements);
    }

    @Override
    public ComputerActionResult invoke(ComputerElement element) {
      actions.add("invoke:" + element.id());
      return ComputerActionResult.success(ComputerActionBackend.UI_AUTOMATION, false);
    }

    @Override
    public ComputerActionResult setValue(ComputerElement element, String value) {
      actions.add("setValue:" + element.id() + ":" + value);
      return ComputerActionResult.success(ComputerActionBackend.UI_AUTOMATION, false);
    }

    @Override
    public ComputerActionResult toggle(ComputerElement element) {
      actions.add("toggle:" + element.id());
      return ComputerActionResult.success(ComputerActionBackend.UI_AUTOMATION, false);
    }

    @Override
    public ComputerActionResult focus(ComputerElement element) {
      actions.add("focus:" + element.id());
      return ComputerActionResult.success(ComputerActionBackend.UI_AUTOMATION, false);
    }
  }

  private static class FakePhysicalInputBackend implements PhysicalInputBackend {
    List<Point> clicks = new ArrayList<>();

    @Override
    public ComputerActionResult click(ComputerBounds bounds) {
      clicks.add(bounds.center());
      return ComputerActionResult.success(ComputerActionBackend.ROBOT, true);
    }

    @Override
    public ComputerActionResult doubleClick(ComputerBounds bounds) {
      return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
    }

    @Override
    public ComputerActionResult moveMouse(int x, int y) {
      return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
    }

    @Override
    public ComputerActionResult typeText(String text) {
      return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
    }

    @Override
    public ComputerActionResult keyPress(String keyStroke) {
      return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
    }

    @Override
    public ComputerActionResult scroll(int wheelAmount) {
      return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
    }

    @Override
    public ComputerActionResult drag(ComputerBounds from, ComputerBounds to) {
      return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
    }
  }
}
