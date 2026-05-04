package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class BlueskyPropertiesTest {

  @Test
  void defaults() {
    BlueskyProperties props = new BlueskyProperties();
    assertFalse(props.isEnabled());
    assertEquals("", props.getHandle());
    assertEquals("", props.getAppPassword());
    assertEquals(300, props.getMaxPostLength());
  }
}
