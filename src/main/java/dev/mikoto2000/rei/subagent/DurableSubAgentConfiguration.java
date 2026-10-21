package dev.mikoto2000.rei.subagent;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 永続化された補助エージェントの構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rei.subagents.durable-enabled", havingValue = "true")
public class DurableSubAgentConfiguration {
  /** 補助エージェントへの委譲で用いる永続化された補助エージェント保存と検索の窓口を返す。 */
  @Bean
  DurableSubAgentRepository durableSubAgentRepository(
      @Qualifier("dataSource") DataSource source, Clock clock) {
    return new DurableSubAgentRepository(source, clock);
  }

  /** 補助エージェントへの委譲で用いる補助エージェント評価の合意サービスを返す。 */
  @Bean
  @ConditionalOnProperty(name = "rei.subagents.consensus-enabled", havingValue = "true")
  SubAgentConsensusService subAgentConsensusService(
      SubAgentProperties properties,
      DurableSubAgentRepository repository,
      SubAgentRunner runner,
      SubAgentRegistry registry) {
    return new SubAgentConsensusService(properties, repository, runner, registry);
  }

  /** 補助エージェントへの委譲で用いる補助エージェント依存関係サービスを返す。 */
  @Bean
  @ConditionalOnProperty(name = "rei.subagents.dag-enabled", havingValue = "true")
  SubAgentDagService subAgentDagService(
      SubAgentProperties properties,
      DurableSubAgentRepository repository,
      SubAgentRunner runner,
      SubAgentRegistry registry,
      ParallelSubAgentDelegator parallel,
      Clock clock) {
    return new SubAgentDagService(properties, repository, runner, registry, parallel, clock);
  }
}
