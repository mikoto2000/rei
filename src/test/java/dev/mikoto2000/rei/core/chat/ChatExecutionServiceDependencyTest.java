package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** チャット実行サービス依存条件の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ChatExecutionServiceDependencyTest {

  /** 会話の実行で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void chatExecutionServiceDoesNotDependOnShellUiOrSoundPresentationComponents()
      throws IOException {
    String source =
        Files.readString(
            Path.of("src/main/java/dev/mikoto2000/rei/core/chat/ChatExecutionService.java"));

    assertFalse(source.contains("dev.mikoto2000.rei.ui."));
    assertFalse(source.contains("ChatResponseNarrator"));
    assertFalse(source.contains("SoundNotificationService"));
    assertFalse(source.contains("SoundNotificationProperties"));
    assertFalse(source.contains("System.out"));
    assertFalse(source.contains("System.err"));
    assertFalse(source.contains("IO.print"));
    assertFalse(source.contains("IO.println"));
  }
}
