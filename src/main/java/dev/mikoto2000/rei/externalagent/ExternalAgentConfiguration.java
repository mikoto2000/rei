package dev.mikoto2000.rei.externalagent;

import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CodexProperties.class,ClaudeCodeProperties.class})
public class ExternalAgentConfiguration {
  @Bean ExternalAgentExecutor externalAgentExecutor(CodexProperties properties,ClaudeCodeProperties claude) {
    return new RoutingExternalAgentExecutor(new CodexExternalAgentExecutor(properties,new ExternalAgentProcessRunner()),new ClaudeCodeExternalAgentExecutor(claude,new ClaudeCodeProcessRunner()));
  }
}
