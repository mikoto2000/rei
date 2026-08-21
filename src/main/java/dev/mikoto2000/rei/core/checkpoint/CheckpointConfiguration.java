package dev.mikoto2000.rei.core.checkpoint;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Checkpoint Store の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class CheckpointConfiguration {

  @Bean
  public CheckpointStore checkpointStore(Clock clock) {
    return new CheckpointStore(clock);
  }
}
