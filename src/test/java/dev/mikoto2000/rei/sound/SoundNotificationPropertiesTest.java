package dev.mikoto2000.rei.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class SoundNotificationPropertiesTest {

  @Test
  void enabledDefaultIsFalse() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    assertFalse(props.isEnabled());
  }

  @Test
  void commandDefaultIsEmptyList() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    assertEquals(0, props.getCommand().size());
  }
}
