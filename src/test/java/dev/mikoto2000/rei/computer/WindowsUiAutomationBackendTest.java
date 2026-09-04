package dev.mikoto2000.rei.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class WindowsUiAutomationBackendTest {

  @Test
  void setValueCanResolveElementAgainByBoundsWhenNameAndAutomationIdAreMissing() {
    FakeAutomation automation = new FakeAutomation();
    WindowsUiAutomationBackend backend = new WindowsUiAutomationBackend(automation);

    ComputerObservation observation = backend.observe(new ComputerObservationRequest(1, 10, true, true));
    ComputerElement edit = observation.elements().getFirst().withId("e1");
    ComputerActionResult result = backend.setValue(edit, "Hello from Rei!");

    assertTrue(result.success());
    assertEquals("Hello from Rei!", automation.window.edit.value);
  }

  @Test
  void observeUsesWindowContainingFocusedElementAsActiveWindow() {
    FakeAutomation automation = new FakeAutomation();
    automation.windows = List.of(new FakeWindow("Explorer", 0, 0, 500, 500),
        new FakeWindow("Untitled - Notepad", 600, 0, 1100, 500));
    automation.focusedElement = automation.windows.get(1).edit;
    WindowsUiAutomationBackend backend = new WindowsUiAutomationBackend(automation);

    ComputerObservation observation = backend.observe(new ComputerObservationRequest(1, 10, true, true));

    assertEquals("Untitled - Notepad", observation.activeWindow().orElseThrow().title());
    assertEquals("Untitled - Notepad", observation.windows().stream().filter(ComputerWindow::active)
        .findFirst().orElseThrow().title());
  }

  @Test
  void observeCollectsDescendantsWithinDepthLimit() {
    FakeAutomation automation = new FakeAutomation();
    automation.window.children = List.of(new FakePanel(List.of(automation.window.edit)));
    WindowsUiAutomationBackend backend = new WindowsUiAutomationBackend(automation);

    ComputerObservation shallow = backend.observe(new ComputerObservationRequest(1, 10, true, true));
    ComputerObservation deep = backend.observe(new ComputerObservationRequest(2, 10, true, true));

    assertEquals(List.of("pane"), shallow.elements().stream().map(ComputerElement::role).toList());
    assertEquals(List.of("pane", "edit"), deep.elements().stream().map(ComputerElement::role).toList());
  }

  @Test
  void observeCanCollectElementsFromAllWindowsWhenRequested() {
    FakeAutomation automation = new FakeAutomation();
    automation.windows = List.of(new FakeWindow("Explorer", 0, 0, 500, 500),
        new FakeWindow("Untitled - Notepad", 600, 0, 1100, 500));
    WindowsUiAutomationBackend backend = new WindowsUiAutomationBackend(automation);

    ComputerObservation observation = backend.observe(new ComputerObservationRequest(1, 10, false, true));

    assertEquals(2, observation.elements().size());
  }

  public static class FakeAutomation {
    final FakeWindow window = new FakeWindow();
    List<FakeWindow> windows = List.of(window);
    Object focusedElement = window.edit;

    public List<FakeWindow> getDesktopWindows() {
      return windows;
    }

    public Object getFocusedElement() {
      return focusedElement;
    }
  }

  public static class FakeWindow {
    final FakeEdit edit = new FakeEdit();
    final String name;
    final FakeRect bounds;
    List<Object> children = List.of(edit);

    public FakeWindow() {
      this("Untitled - Notepad", 0, 0, 800, 600);
    }

    public FakeWindow(String name, int left, int top, int right, int bottom) {
      this.name = name;
      this.bounds = new FakeRect(left, top, right, bottom);
      this.edit.bounds = new FakeRect(left + 10, top + 10, right - 10, bottom - 10);
    }

    public String getName() {
      return name;
    }

    public String getClassName() {
      return "Notepad";
    }

    public FakeRect getBoundingRectangle() {
      return bounds;
    }

    public List<Object> getChildren(boolean cached) {
      return children;
    }
  }

  public static class FakePanel {
    final List<Object> children;

    public FakePanel(List<Object> children) {
      this.children = children;
    }

    public String getControlType() {
      return "Pane";
    }

    public String getName() {
      return "";
    }

    public boolean isEnabled() {
      return true;
    }

    public boolean isOffScreen() {
      return false;
    }

    public FakeRect getBoundingRectangle() {
      return new FakeRect(5, 5, 795, 595);
    }

    public List<Object> getChildren(boolean cached) {
      return children;
    }
  }

  public static class FakeEdit {
    String value;
    FakeRect bounds = new FakeRect(10, 10, 790, 590);

    public String getControlType() {
      return "Edit";
    }

    public String getName() {
      return "";
    }

    public String getAutomationId() {
      return "";
    }

    public String getClassName() {
      return "RichEditD2DPT";
    }

    public boolean isEnabled() {
      return true;
    }

    public boolean isOffScreen() {
      return false;
    }

    public FakeRect getBoundingRectangle() {
      return bounds;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  public record FakeRect(int left, int top, int right, int bottom) {
  }
}
