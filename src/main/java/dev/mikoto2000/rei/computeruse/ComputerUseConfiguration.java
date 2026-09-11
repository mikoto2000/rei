package dev.mikoto2000.rei.computeruse;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ComputerUseProperties.class)
@ConditionalOnProperty(name = "rei.computer-use.enabled", havingValue = "true")
public class ComputerUseConfiguration {
  @Bean RobotDriver computerRobotDriver() { return new AwtRobotDriver(); }
  @Bean Sleeper computerSleeper() { return Thread::sleep; }
  @Bean ScreenCapture computerScreenCapture(RobotDriver driver) { return new RobotScreenCapture(driver); }
  @Bean ComputerInput computerInput(RobotDriver driver, Sleeper sleeper, ComputerUseProperties properties) {
    return new RobotComputerInput(driver, new ClipboardPaste(
        () -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard(), sleeper, properties.clipboardMillis()), sleeper);
  }
  @Bean UiStabilizer computerUiStabilizer(Sleeper sleeper, ComputerUseProperties properties) {
    return new FixedUiStabilizer(sleeper, properties.stabilizationMillis());
  }
  @Bean SafetyPolicy computerSafetyPolicy() { return SafetyPolicy.lowRiskOnly(); }
  @Bean ComputerVisionModel computerVisionModel(LlmModelProvider provider, ModelHolderService current,
      CommandCancellationService cancellation, ComputerUseProperties properties) {
    return new SpringAiComputerVisionModel(provider.computerUseChatModel(),
        () -> new org.springframework.ai.openai.OpenAiChatOptions.Builder(provider.chatOptions(LlmFeature.COMPUTER_USE, current.get())),
        cancellation::isCancellationRequested, properties.repairs());
  }
  @Bean ComputerUseService computerUseService(ScreenCapture capture, ComputerVisionModel model, ComputerInput input,
      UiStabilizer stabilizer, SafetyPolicy safety, CommandCancellationService cancellation,
      AgentEventFactory factory, AgentEventPublisher publisher, ComputerUseProperties properties) {
    return new ComputerUseService(capture, model, input, stabilizer, safety, cancellation::isCancellationRequested,
        progress -> publisher.publish(factory.computerUseProgress(progress)), properties.maxSteps(), properties.historyLimit());
  }
  @Bean ComputerUseTools computerUseTools(ComputerUseService service, CommandCancellationService cancellation,
      AgentEventFactory factory, AgentEventPublisher publisher) {
    return new ComputerUseTools(service, cancellation, factory, publisher);
  }
}
