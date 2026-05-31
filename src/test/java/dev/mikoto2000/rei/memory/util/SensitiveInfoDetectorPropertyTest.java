package dev.mikoto2000.rei.memory.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;

class SensitiveInfoDetectorPropertyTest {

  private final SensitiveInfoDetector detector = new SensitiveInfoDetector();

  // Feature: ai-memory-consolidation, Property 17: 機密情報検出と保護
  @Property(tries = 100)
  void detectsEmailPattern(@ForAll @AlphaChars @NotBlank String local, @ForAll @AlphaChars @NotBlank String domain) {
    String content = local + "@" + domain + ".com";
    assertTrue(detector.containsSensitiveInfo(content));
  }

  // Feature: ai-memory-consolidation, Property 17: 機密情報検出と保護
  @Property(tries = 100)
  void detectsSecretKeyPattern(@ForAll @AlphaChars @NotBlank String key, @ForAll @NotBlank String value) {
    String content = key + " password=" + value;
    assertTrue(detector.containsSensitiveInfo(content));
  }

  // Feature: ai-memory-consolidation, Property 17: 機密情報検出と保護
  @Property(tries = 100)
  void noSensitivePatternMeansFalse(@ForAll @AlphaChars String text) {
    String content = "plain " + text.replace("@", "").replace("password", "pass") + " message";
    assertFalse(detector.containsSensitiveInfo(content));
  }
}
