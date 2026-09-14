package dev.mikoto2000.rei.externalagent;

import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CodexProperties.class)
public class ExternalAgentConfiguration {
  @Bean ExternalAgentExecutor externalAgentExecutor(CodexProperties properties) {
    return new CodexExternalAgentExecutor(properties, new ExternalAgentProcessRunner());
  }
}
