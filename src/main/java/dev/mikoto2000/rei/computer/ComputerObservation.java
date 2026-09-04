package dev.mikoto2000.rei.computer;

import java.util.List;
import java.util.Optional;

public record ComputerObservation(
    String observationId,
    Optional<ComputerWindow> activeWindow,
    List<ComputerWindow> windows,
    List<ComputerElement> elements) {

  public ComputerObservation {
    activeWindow = activeWindow == null ? Optional.empty() : activeWindow;
    windows = windows == null ? List.of() : List.copyOf(windows);
    elements = elements == null ? List.of() : List.copyOf(elements);
  }
}
