package dev.mikoto2000.rei.core.recentchanges;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Recent Changes の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class RecentChangesConfiguration {

  @org.springframework.context.annotation.Scope(value = "reiProject", proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
  @Bean
  public RecentChanges recentChanges(Clock clock) {
    return new RecentChanges(RecentChanges.DEFAULT_MAX_ENTRIES, clock);
  }
}