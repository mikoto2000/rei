package dev.mikoto2000.rei.dot;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DotProperties.class)
public class DotConfiguration {
  @Bean
  DotRequestRepository dotRequestRepository(
      @Value("${rei.data-dir}") String root,
      Clock clock,
      DotProperties properties,
      org.springframework.core.env.Environment env) {
    if (properties.parentLlmEnabled()
        && (!properties.enabled()
            || !env.getProperty("rei.conversation.concurrent-enabled", Boolean.class, false)
            || !env.getProperty("rei.tool-permission.enabled", Boolean.class, false)
            || !env.getProperty("rei.checkpoint.enabled", Boolean.class, true)))
      throw new IllegalArgumentException(
          "Parent Dot delegation requires persistent concurrent Run tracking, checkpoint and tool"
              + " permission");
    var repo = new DotRequestRepository(Path.of(root), clock);
    repo.limits(
        properties.activeLimit(),
        properties.queueLimit(),
        properties.claimLease(),
        Duration.ofDays(properties.resultRetentionDays()),
        Duration.ofDays(properties.requestRetentionDays()));
    return repo;
  }

  @Bean
  @ConditionalOnProperty(name = "rei.mcp-server.enabled", havingValue = "true")
  DotMcpTools dotMcpTools(
      DotRequestRepository repo,
      DotProperties properties,
      org.springframework.core.env.Environment env) {
    var allowed = new HashSet<String>();
    for (String id : env.getProperty("rei.mcp-server.allowed-projects", "").split(","))
      if (!id.isBlank()) allowed.add(id.trim());
    allowed.retainAll(properties.allowedProjects());
    return new DotMcpTools(repo, allowed, properties.enabled());
  }
}
