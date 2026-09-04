package dev.mikoto2000.rei.computer;

public interface UiAutomationBackend {
  ComputerObservation observe(ComputerObservationRequest request);

  ComputerActionResult invoke(ComputerElement element);

  ComputerActionResult setValue(ComputerElement element, String value);

  ComputerActionResult toggle(ComputerElement element);

  ComputerActionResult focus(ComputerElement element);
}
