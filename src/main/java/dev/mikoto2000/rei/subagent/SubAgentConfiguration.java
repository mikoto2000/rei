package dev.mikoto2000.rei.subagent;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubAgentProperties.class)
public class SubAgentConfiguration {
  @Bean SubAgentToolPolicy subAgentToolPolicy(SubAgentToolCatalog catalog) { return new SubAgentToolPolicy(catalog.knownNames()); }
  @Bean SubAgentDefinitionLoader subAgentDefinitionLoader(SubAgentToolPolicy policy, SubAgentProperties properties,
      ModelHolderService current, LlmModelProvider provider) {
    // Resolve locally against administrator-configured IDs; validation must not require a live provider.
    return new SubAgentDefinitionLoader(policy, model -> properties.getModels().contains(model)
        || model.equals(current.get()) || model.equals(provider.model(LlmFeature.CHAT, current.get())));
  }
  @Bean SubAgentRegistry subAgentRegistry(SubAgentProperties properties, SubAgentDefinitionLoader loader) {
    var registry = new SubAgentRegistry(properties.getDirectory(), loader);
    for (String error : registry.reload()) org.slf4j.LoggerFactory.getLogger(SubAgentRegistry.class).warn("{}", error);
    return registry;
  }
  @Bean SubAgentRunner subAgentRunner(SubAgentRegistry registry, SubAgentToolPolicy policy, SubAgentToolCatalog catalog,
      LlmModelProvider provider, ModelHolderService current, CommandCancellationService cancellation,
      AgentEventFactory events, AgentEventPublisher publisher, Clock clock) {
    return new SubAgentRunner(registry, policy, model -> provider.subAgentChatModel(), model -> {
      var options = provider.chatOptions(LlmFeature.CHAT, current.get());
      if (model != null) options.setModel(model);
      return options;
    }, catalog::createTools, cancellation, events, publisher, clock);
  }
}
