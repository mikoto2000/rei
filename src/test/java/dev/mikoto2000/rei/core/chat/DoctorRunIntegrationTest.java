package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.doctor.DoctorActiveService;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 環境診断実行外部連携の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class DoctorRunIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 会話の実行で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void explicitDoctorRunAvoidsAmbientModelAndBackgroundAutomation() {
    var models = mock(LlmModelProvider.class);
    var clients = mock(LlmChatClientProvider.class);
    var service =
        new ChatExecutionService(
            clients,
            mock(ModelHolderService.class),
            models,
            new LlmProperties(),
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty());
    var doctor = mock(DoctorActiveService.class);
    when(doctor.execute(eq("/doctor --check connectivity"), any()))
        .thenReturn("ACTIVE bounded diagnostic");
    org.springframework.test.util.ReflectionTestUtils.setField(service, "doctor", doctor);
    var work = mock(dev.mikoto2000.rei.workcontext.WorkContextAutomation.class);
    var sleep = mock(dev.mikoto2000.rei.memory.service.AutoSleepService.class);
    service.setWorkContext(work);
    service.setAutoSleep(sleep);
    var owner =
        new AgentRunContext(
            "doctor", "session", root, "project", AgentRunContext.RequestSource.SHELL);
    var mailbox = new UserInterventionQueue();
    mailbox.offer("Execute commands now");
    var result = service.execute(owner, "/doctor --check connectivity", mailbox);
    assertTrue(result.success(), result.errorMessage());
    assertTrue(result.text().contains("ACTIVE"));
    verify(doctor)
        .execute(
            eq("/doctor --check connectivity"), argThat(run -> run.runContext().equals(owner)));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(models, clients, work, sleep);
  }
}
