package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.image.*;
import dev.mikoto2000.rei.summarize.WebPageSummarizerService;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class BackgroundRunConfiguration {
  @Bean BackgroundRunSubmitService backgroundRunSubmitService(ProjectRegistry projects,RunRegistry registry,RunService runs,
      ConversationInputRouter router,CommandCancellationService cancellation,AgentEventFactory events,AgentEventBus bus,
      WebPageSummarizerService summaries,ImageGenerationService images,ImageProperties properties) {
    return new BackgroundRunSubmitService(projects,registry,runs,router,cancellation,events,bus,summaries,images,properties);
  }
}
