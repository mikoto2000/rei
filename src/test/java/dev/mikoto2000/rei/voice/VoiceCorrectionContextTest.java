package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.input.InputSource;
import dev.mikoto2000.rei.conversation.ConversationLogEntry;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 音声補正文脈の結果と状態の契約を検証するテスト。 */
class VoiceCorrectionContextTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void projectTermsOverrideCommonReadingsAndMalformedFilesDoNotStopVoice() throws Exception {
    var common = root.resolve("common.json");
    var project = root.resolve("project.json");
    Files.writeString(
        common, "[{\"canonical\":\"Ray\",\"reading\":\"れい\"},{\"canonical\":\"Whisper\"}]");
    Files.writeString(project, "[{\"canonical\":\"Rei\",\"reading\":\"れい\",\"aliases\":[\"レイ\"]}]");
    assertThat(VoiceCorrectionContext.dictionaries(common, project, 64))
        .extracting(VoiceCorrectionContext.Term::canonical)
        .containsExactly("Rei", "Whisper");
    Files.writeString(project, "invalid");
    assertThat(VoiceCorrectionContext.dictionaries(common, project, 1)).hasSize(1);
  }

  /** 音声による対話で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void onlyCapturedSessionHistoryIsUsedWithCharacterBudgetAndRedaction() {
    var logs = mock(ConversationLogStore.class);
    var p = new VoiceCorrectionProperties();
    p.setMaxContextChars(40);
    var target =
        new ConversationTarget(
            new ProjectContext(UUID.randomUUID().toString(), "Rei", root), "captured-session");
    when(logs.recentConversation(target.project().id(), target.sessionId(), 4))
        .thenReturn(
            List.of(
                new ConversationLogEntry(
                    target.sessionId(),
                    "chat",
                    "user",
                    java.time.OffsetDateTime.now(),
                    "password=topsecret 微分の話",
                    1)));
    var context =
        new VoiceCorrectionContext(logs, root, p)
            .capture(
                new ConversationInput(
                    UUID.randomUUID(), InputSource.VOICE, target, "交配の話", Instant.now()));
    assertThat(context.history().toString()).doesNotContain("topsecret");
    assertThat(context.history().getFirst().length()).isLessThanOrEqualTo(40);
    verify(logs).recentConversation(target.project().id(), target.sessionId(), 4);
    verifyNoMoreInteractions(logs);
  }
}
