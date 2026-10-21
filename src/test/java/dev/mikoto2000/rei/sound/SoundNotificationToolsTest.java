package dev.mikoto2000.rei.sound;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import org.junit.jupiter.api.Test;

/** 音通知通知ツール一覧の結果と状態の契約を検証するテスト。 */
class SoundNotificationToolsTest {

  /** 音による案内で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void webRequestCannotTriggerSoundTool() {
    var service = mock(SoundNotificationService.class);
    var tools = new SoundNotificationTools(service);
    var context =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "web",
            "session",
            java.nio.file.Path.of("."),
            "project",
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB);
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(context)) {
      org.assertj.core.api.Assertions.assertThat(tools.notify("web answer")).contains("Shell");
    }
    org.mockito.Mockito.verifyNoInteractions(service);
  }

  /** 音による案内で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shellRequestCanTriggerSoundTool() {
    var service = mock(SoundNotificationService.class);
    var tools = new SoundNotificationTools(service);
    var context =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "shell", "session", java.nio.file.Path.of("."));
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(context)) {
      tools.notify("shell answer");
    }
    verify(service).notify("shell answer");
  }

  /** 音による案内で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void notifyDelegatesToService() {
    SoundNotificationService service = mock(SoundNotificationService.class);
    SoundNotificationTools tools = new SoundNotificationTools(service);

    tools.notify("テストメッセージ");

    verify(service).notify("テストメッセージ");
  }

  /** 通知について、以外未指定の文字列表現を返すことを確認する。 */
  @Test
  void notifyReturnsNonNullString() {
    SoundNotificationService service = mock(SoundNotificationService.class);
    SoundNotificationTools tools = new SoundNotificationTools(service);

    String result = tools.notify("テストメッセージ");

    assertNotNull(result, "戻り値が null でないべき");
  }
}
