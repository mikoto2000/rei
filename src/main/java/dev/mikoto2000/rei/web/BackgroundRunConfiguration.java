package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.BackgroundRunSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.image.ImageGenerationService;
import dev.mikoto2000.rei.image.ImageProperties;
import dev.mikoto2000.rei.summarize.WebPageSummarizerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** バックグラウンド実行の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class BackgroundRunConfiguration {
  /** Web APIによる操作で用いるバックグラウンド実行受付サービスを返す。 */
  @Bean
  BackgroundRunSubmitService backgroundRunSubmitService(
      ProjectRegistry projects,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      CommandCancellationService cancellation,
      AgentEventFactory events,
      AgentEventBus bus,
      WebPageSummarizerService summaries,
      ImageGenerationService images,
      ImageProperties properties) {
    return new BackgroundRunSubmitService(
        projects, registry, runs, router, cancellation, events, bus, summaries, images, properties);
  }
}
