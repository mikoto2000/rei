package dev.mikoto2000.rei.computeruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 画面操作利用機能の構成の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ComputerUseConfigurationTest {
  /** 画面操作の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void selectsShowUiOnlyWhenConfigured() {
    runner
        .withPropertyValues("rei.computer-use.enabled=true", "rei.computer-use.grounding=uitars")
        .run(
            context -> assertThat(context).hasNotFailed().hasSingleBean(ComputerVisionModel.class));
    runner
        .withPropertyValues("rei.computer-use.enabled=true", "rei.computer-use.grounding=showui")
        .run(
            context ->
                assertThat(context.getBean(ComputerVisionModel.class))
                    .isInstanceOf(ShowUiComputerVisionModel.class));
    runner
        .withPropertyValues("rei.computer-use.enabled=true", "rei.computer-use.grounding=typo")
        .run(context -> assertThat(context).hasFailed());
  }

  /** 検証に使う診断情報ディレクトリ。 */
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path diagnosticsDirectory;

  /** 設定済みディレクトリを利用することを確認する。 */
  @Test
  void diagnosticsAreOptInAndUseConfiguredDirectory() {
    runner
        .withPropertyValues("rei.computer-use.enabled=true")
        .run(context -> assertThat(context.getBean(ComputerDiagnostics.class).begin()).isNull());
    runner
        .withPropertyValues(
            "rei.computer-use.enabled=true",
            "rei.computer-use.diagnostics.enabled=true",
            "rei.computer-use.diagnostics.directory=" + diagnosticsDirectory)
        .run(
            context ->
                assertThat(context.getBean(ComputerDiagnostics.class).begin().getParent())
                    .isEqualTo(diagnosticsDirectory));
  }

  /** 検証に使う実行の担当。 */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(ComputerUseConfiguration.class)
          .withBean(
              FullAutoOptions.class,
              () -> new FullAutoOptions(new org.springframework.boot.DefaultApplicationArguments()))
          .withBean(
              LlmModelProvider.class,
              () -> new LlmModelProvider(mock(ChatModel.class), new LlmProperties()))
          .withBean(ModelHolderService.class, () -> new ModelHolderService("vision"))
          .withBean(CommandCancellationService.class)
          .withBean(
              AgentEventFactory.class, () -> new AgentEventFactory(java.time.Clock.systemUTC()))
          .withBean(AgentEventPublisher.class, () -> event -> {});

  /** 無効状態別既定およびについて、必要時に取得するデスクトップ連携の窓口一覧を作成することを確認する。 */
  @Test
  void disabledByDefaultAndCreatesLazyDesktopAdaptersWhenEnabled() {
    runner.run(context -> assertThat(context).doesNotHaveBean(ComputerUseTools.class));
    runner
        .withPropertyValues("rei.computer-use.enabled=true")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ComputerUseTools.class)
                  .hasSingleBean(ScreenCapture.class)
                  .hasSingleBean(ComputerInput.class)
                  .hasSingleBean(ComputerVisionModel.class);
              assertThat(context.getBean(ComputerUseProperties.class).maxSteps()).isEqualTo(20);
            });
  }

  /** 上限のない設定を受け付けないことを確認する。 */
  @Test
  void rejectsUnboundedSettings() {
    runner
        .withPropertyValues("rei.computer-use.enabled=true", "rei.computer-use.max-steps=0")
        .run(context -> assertThat(context).hasFailed());
  }
}
