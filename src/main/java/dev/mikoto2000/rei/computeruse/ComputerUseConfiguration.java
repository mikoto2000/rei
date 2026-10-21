package dev.mikoto2000.rei.computeruse;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 画面操作利用の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ComputerUseProperties.class)
@ConditionalOnProperty(name = "rei.computer-use.enabled", havingValue = "true")
public class ComputerUseConfiguration {
  /** 画面操作の支援で用いる画面操作Robot操作の実行先を返す。 */
  @Bean
  RobotDriver computerRobotDriver() {
    return new AwtRobotDriver();
  }

  /** 画面操作の支援で用いる画面操作診断情報を返す。 */
  @Bean
  ComputerDiagnostics computerDiagnostics(
      @org.springframework.beans.factory.annotation.Value(
              "${rei.computer-use.diagnostics.enabled:false}")
          boolean enabled,
      @org.springframework.beans.factory.annotation.Value(
              "${rei.computer-use.diagnostics.directory:}")
          String directory) {
    return new ComputerDiagnostics(
        !enabled
            ? null
            : directory.isBlank()
                ? dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
                    .resolve("computer-use-diagnostics")
                : java.nio.file.Path.of(directory));
  }

  /** 画面操作の支援で用いる画面操作待機の実行先を返す。 */
  @Bean
  Sleeper computerSleeper() {
    return Thread::sleep;
  }

  /** 画面操作の支援で用いる画面操作画面取得記録を返す。 */
  @Bean
  ScreenCapture computerScreenCapture(RobotDriver driver) {
    return new RobotScreenCapture(driver);
  }

  /** 画面操作の支援で用いる画面操作入力を返す。 */
  @Bean
  ComputerInput computerInput(
      RobotDriver driver, Sleeper sleeper, ComputerUseProperties properties) {
    return new RobotComputerInput(
        driver,
        new ClipboardPaste(
            () -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard(),
            sleeper,
            properties.clipboardMillis()),
        sleeper);
  }

  /** 画面操作の支援で用いる画面操作表示Stabilizerを返す。 */
  @Bean
  UiStabilizer computerUiStabilizer(Sleeper sleeper, ComputerUseProperties properties) {
    return new FixedUiStabilizer(sleeper, properties.stabilizationMillis());
  }

  /** 画面操作の支援で用いる画面操作Safety判断基準を返す。 */
  @Bean
  SafetyPolicy computerSafetyPolicy(FullAutoOptions options) {
    return options.enabled() ? SafetyPolicy.fullAuto() : SafetyPolicy.lowRiskOnly();
  }

  /** 画面操作の支援で用いる画面操作画像認識モデルを返す。 */
  @Bean
  ComputerVisionModel computerVisionModel(
      LlmModelProvider provider,
      ModelHolderService current,
      CommandCancellationService cancellation,
      ComputerUseProperties properties,
      @org.springframework.beans.factory.annotation.Value("${rei.computer-use.grounding:generic}")
          String grounding) {
    if ("showui".equals(grounding) || "uitars".equals(grounding)) {
      var planner =
          new SpringAiComputerVisionModel(
              provider.chatModel(LlmFeature.COMPUTER_USE_PLANNER),
              () -> provider.chatOptions(LlmFeature.COMPUTER_USE_PLANNER, current.get()).mutate(),
              cancellation::isCancellationRequested,
              properties.repairs());
      return new ShowUiComputerVisionModel(
          planner::decideOverview,
          provider.computerUseChatModel(),
          () -> provider.chatOptions(LlmFeature.COMPUTER_USE, current.get()).mutate(),
          cancellation::isCancellationRequested,
          "uitars".equals(grounding) ? GroundingProtocol.UITARS : GroundingProtocol.SHOWUI,
          new PlannerGroundingVerifier(
              provider.chatModel(LlmFeature.COMPUTER_USE_PLANNER),
              () -> provider.chatOptions(LlmFeature.COMPUTER_USE_PLANNER, current.get()).mutate(),
              cancellation::isCancellationRequested));
    }
    if (!"generic".equals(grounding))
      throw new IllegalArgumentException("Unknown computer-use grounding: " + grounding);
    return new SpringAiComputerVisionModel(
        provider.computerUseChatModel(),
        () -> provider.chatOptions(LlmFeature.COMPUTER_USE, current.get()).mutate(),
        cancellation::isCancellationRequested,
        properties.repairs());
  }

  /** 画面操作の支援で用いる画面操作利用サービスを返す。 */
  @Bean
  ComputerUseService computerUseService(
      ScreenCapture capture,
      ComputerVisionModel model,
      ComputerInput input,
      UiStabilizer stabilizer,
      SafetyPolicy safety,
      CommandCancellationService cancellation,
      AgentEventFactory factory,
      AgentEventPublisher publisher,
      ComputerUseProperties properties,
      ComputerDiagnostics diagnostics) {
    return new ComputerUseService(
        capture,
        model,
        input,
        stabilizer,
        safety,
        cancellation::isCancellationRequested,
        progress -> publisher.publish(factory.computerUseProgress(progress)),
        properties.maxSteps(),
        properties.historyLimit(),
        diagnostics,
        new WindowsFocusProbe(cancellation::isCancellationRequested));
  }

  /** 画面操作の支援で用いる画面操作利用ツール一覧を返す。 */
  @Bean
  ComputerUseTools computerUseTools(
      ComputerUseService service,
      CommandCancellationService cancellation,
      AgentEventFactory factory,
      AgentEventPublisher publisher) {
    return new ComputerUseTools(service, cancellation, factory, publisher);
  }
}
