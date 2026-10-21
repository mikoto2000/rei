package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 補助エージェントの構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubAgentProperties.class)
public class SubAgentConfiguration {
  /** 補助エージェントへの委譲で用いる補助エージェントツール判断基準を返す。 */
  @Bean
  SubAgentToolPolicy subAgentToolPolicy(SubAgentToolCatalog catalog) {
    return new SubAgentToolPolicy(catalog.knownNames());
  }

  /** 補助エージェントへの委譲で用いる補助エージェント定義読み込みの窓口を返す。 */
  @Bean
  SubAgentDefinitionLoader subAgentDefinitionLoader(
      SubAgentToolPolicy policy,
      SubAgentProperties properties,
      ModelHolderService current,
      LlmModelProvider provider) {
    // Resolve locally against administrator-configured IDs; validation must not require a live
    // provider.
    return new SubAgentDefinitionLoader(
        policy,
        model ->
            properties.getModels().contains(model)
                || model.equals(current.get())
                || model.equals(provider.model(LlmFeature.CHAT, current.get())));
  }

  /** 補助エージェントへの委譲で用いる補助エージェント登録台帳を返す。 */
  @Bean
  SubAgentRegistry subAgentRegistry(
      SubAgentProperties properties, SubAgentDefinitionLoader loader) {
    var registry = new SubAgentRegistry(properties.getDirectory(), loader);
    for (String error : registry.reload())
      org.slf4j.LoggerFactory.getLogger(SubAgentRegistry.class).warn("{}", error);
    return registry;
  }

  /** 補助エージェントへの委譲で用いる補助エージェント実行の担当を返す。 */
  @Bean
  SubAgentRunner subAgentRunner(
      SubAgentRegistry registry,
      SubAgentToolPolicy policy,
      SubAgentToolCatalog catalog,
      LlmModelProvider provider,
      ModelHolderService current,
      CommandCancellationService cancellation,
      AgentEventFactory events,
      AgentEventPublisher publisher,
      Clock clock) {
    return new SubAgentRunner(
        registry,
        policy,
        model -> provider.subAgentChatModel(),
        model -> {
          var options = provider.chatOptions(LlmFeature.CHAT, current.get());
          return model == null ? options : options.mutate().model(model).build();
        },
        catalog::createTools,
        cancellation,
        events,
        publisher,
        clock);
  }
}
