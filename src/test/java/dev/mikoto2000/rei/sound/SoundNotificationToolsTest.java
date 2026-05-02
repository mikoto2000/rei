package dev.mikoto2000.rei.sound;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class SoundNotificationToolsTest {

  @Test
  void notifyDelegatesToService() {
    SoundNotificationService service = mock(SoundNotificationService.class);
    SoundNotificationTools tools = new SoundNotificationTools(service);

    tools.notify("テストメッセージ");

    verify(service).notify("テストメッセージ");
  }

  @Test
  void notifyReturnsNonNullString() {
    SoundNotificationService service = mock(SoundNotificationService.class);
    SoundNotificationTools tools = new SoundNotificationTools(service);

    String result = tools.notify("テストメッセージ");

    assertNotNull(result, "戻り値が null でないべき");
  }
}
