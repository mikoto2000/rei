package dev.mikoto2000.rei.core.relatedgraph;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Related File Graph の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class RelatedFileGraphConfiguration {

  @Bean
  public RelatedFileGraph relatedFileGraph(Clock clock) {
    return new RelatedFileGraph(RelatedFileGraph.DEFAULT_MAX_RELATIONS, clock);
  }
}
