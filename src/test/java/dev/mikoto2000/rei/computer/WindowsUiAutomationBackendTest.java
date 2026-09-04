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

  public static class FakeAutomation {
    final FakeWindow window = new FakeWindow();

    public List<FakeWindow> getDesktopWindows() {
      return List.of(window);
    }
  }

  public static class FakeWindow {
    final FakeEdit edit = new FakeEdit();

    public String getName() {
      return "Untitled - Notepad";
    }

    public String getClassName() {
      return "Notepad";
    }

    public FakeRect getBoundingRectangle() {
      return new FakeRect(0, 0, 800, 600);
    }

    public List<FakeEdit> getChildren(boolean cached) {
      return List.of(edit);
    }
  }

  public static class FakeEdit {
    String value;

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
      return new FakeRect(10, 10, 790, 590);
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  public record FakeRect(int left, int top, int right, int bottom) {
  }
}
