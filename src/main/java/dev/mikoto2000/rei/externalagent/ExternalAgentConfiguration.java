package dev.mikoto2000.rei.externalagent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 外部エージェントの構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CodexProperties.class, ClaudeCodeProperties.class})
public class ExternalAgentConfiguration {
  /** 外部エージェントへの委譲で用いる外部エージェント実行先を返す。 */
  @Bean
  ExternalAgentExecutor externalAgentExecutor(
      CodexProperties properties, ClaudeCodeProperties claude) {
    return new RoutingExternalAgentExecutor(
        new CodexExternalAgentExecutor(properties, new ExternalAgentProcessRunner()),
        new ClaudeCodeExternalAgentExecutor(claude, new ClaudeCodeProcessRunner()));
  }

  /** 外部エージェントへの委譲で用いる独立した実装サービスを返す。 */
  @Bean
  IsolatedImplementationService isolatedImplementationService() {
    return new IsolatedImplementationService(
        dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
            .resolve("isolated-implementations"),
        new ExternalAgentProcessRunner(),
        cancelled ->
            new dev.mikoto2000.rei.core.SelfPatchReviewService(
                new dev.mikoto2000.rei.core.service.SystemShellService(), cancelled));
  }
}
