package dev.mikoto2000.rei.computer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RobotPhysicalInputBackendTest {

  @Test
  void constructorDoesNotFailWhenRobotIsUnavailableUntilActionIsRequested() {
    RobotPhysicalInputBackend backend = new RobotPhysicalInputBackend(() -> {
      throw new IllegalStateException("Robot backend is not available");
    });

    ComputerActionResult result = backend.click(new ComputerBounds(0, 0, 10, 10));

    assertFalse(result.success());
    assertTrue(result.failureReason().orElseThrow().contains("Robot backend is not available"));
  }
}
