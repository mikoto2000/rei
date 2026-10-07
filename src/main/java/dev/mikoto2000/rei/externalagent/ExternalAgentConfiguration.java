package dev.mikoto2000.rei.externalagent;

import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CodexProperties.class,ClaudeCodeProperties.class})
public class ExternalAgentConfiguration {
  @Bean ExternalAgentExecutor externalAgentExecutor(CodexProperties properties,ClaudeCodeProperties claude) {
    return new RoutingExternalAgentExecutor(new CodexExternalAgentExecutor(properties,new ExternalAgentProcessRunner()),new ClaudeCodeExternalAgentExecutor(claude,new ClaudeCodeProcessRunner()));
  }
  @Bean IsolatedImplementationService isolatedImplementationService() {
    return new IsolatedImplementationService(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("isolated-implementations"),new ExternalAgentProcessRunner(),cancelled->new dev.mikoto2000.rei.core.SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService(),cancelled));
  }
}
