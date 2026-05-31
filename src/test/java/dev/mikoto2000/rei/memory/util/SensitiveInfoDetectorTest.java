package dev.mikoto2000.rei.memory.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SensitiveInfoDetectorTest {

  private final SensitiveInfoDetector detector = new SensitiveInfoDetector();

  @Test
  void detectsEmail() {
    assertTrue(detector.containsSensitiveInfo("user@example.com"));
  }

  @Test
  void detectsSecretPattern() {
    assertTrue(detector.containsSensitiveInfo("password=abc123"));
  }

  @Test
  void ignoresNormalText() {
    assertFalse(detector.containsSensitiveInfo("hello world"));
  }
}
