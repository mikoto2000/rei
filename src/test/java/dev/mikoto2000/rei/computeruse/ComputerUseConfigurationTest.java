package dev.mikoto2000.rei.computeruse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.chat.model.ChatModel;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;

class ComputerUseConfigurationTest {
  @Test void selectsShowUiOnlyWhenConfigured() {
    runner.withPropertyValues("rei.computer-use.enabled=true", "rei.computer-use.grounding=uitars")
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ComputerVisionModel.class));
    runner.withPropertyValues("rei.computer-use.enabled=true", "rei.computer-use.grounding=showui")
        .run(context -> assertThat(context.getBean(ComputerVisionModel.class)).isInstanceOf(ShowUiComputerVisionModel.class));
    runner.withPropertyValues("rei.computer-use.enabled=true", "rei.computer-use.grounding=typo")
        .run(context -> assertThat(context).hasFailed());
  }
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path diagnosticsDirectory;
  @Test void diagnosticsAreOptInAndUseConfiguredDirectory() {
    runner.withPropertyValues("rei.computer-use.enabled=true").run(context ->
        assertThat(context.getBean(ComputerDiagnostics.class).begin()).isNull());
    runner.withPropertyValues("rei.computer-use.enabled=true", "rei.computer-use.diagnostics.enabled=true",
        "rei.computer-use.diagnostics.directory=" + diagnosticsDirectory).run(context ->
        assertThat(context.getBean(ComputerDiagnostics.class).begin().getParent()).isEqualTo(diagnosticsDirectory));
  }
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(ComputerUseConfiguration.class)
      .withBean(FullAutoOptions.class, () -> new FullAutoOptions(new org.springframework.boot.DefaultApplicationArguments()))
      .withBean(LlmModelProvider.class, () -> new LlmModelProvider(mock(ChatModel.class),new LlmProperties()))
      .withBean(ModelHolderService.class, () -> new ModelHolderService("vision"))
      .withBean(CommandCancellationService.class)
      .withBean(AgentEventFactory.class, () -> new AgentEventFactory(java.time.Clock.systemUTC()))
      .withBean(AgentEventPublisher.class, () -> event -> {});
  @Test void disabledByDefaultAndCreatesLazyDesktopAdaptersWhenEnabled() {
    runner.run(context -> assertThat(context).doesNotHaveBean(ComputerUseTools.class));
    runner.withPropertyValues("rei.computer-use.enabled=true").run(context -> {
      assertThat(context).hasNotFailed().hasSingleBean(ComputerUseTools.class)
          .hasSingleBean(ScreenCapture.class).hasSingleBean(ComputerInput.class).hasSingleBean(ComputerVisionModel.class);
      assertThat(context.getBean(ComputerUseProperties.class).maxSteps()).isEqualTo(20);
    });
  }
  @Test void rejectsUnboundedSettings() {
    runner.withPropertyValues("rei.computer-use.enabled=true", "rei.computer-use.max-steps=0")
        .run(context -> assertThat(context).hasFailed());
  }
}
