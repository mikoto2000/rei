package dev.mikoto2000.rei.sound;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;

class SoundNotificationToolsTest {

  @Test
  void webRequestCannotTriggerSoundTool() {
    var service = mock(SoundNotificationService.class);
    var tools = new SoundNotificationTools(service);
    var context = new dev.mikoto2000.rei.core.chat.AgentRunContext("web", "session",
        java.nio.file.Path.of("."), "project",
        dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB);
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(context)) {
      org.assertj.core.api.Assertions.assertThat(tools.notify("web answer")).contains("Shell");
    }
    org.mockito.Mockito.verifyNoInteractions(service);
  }

  @Test
  void shellRequestCanTriggerSoundTool() {
    var service = mock(SoundNotificationService.class);
    var tools = new SoundNotificationTools(service);
    var context = new dev.mikoto2000.rei.core.chat.AgentRunContext("shell", "session",
        java.nio.file.Path.of("."));
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(context)) {
      tools.notify("shell answer");
    }
    verify(service).notify("shell answer");
  }

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
