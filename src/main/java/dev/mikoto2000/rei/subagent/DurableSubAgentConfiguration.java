package dev.mikoto2000.rei.subagent;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="rei.subagents.durable-enabled",havingValue="true")
public class DurableSubAgentConfiguration {
  @Bean DurableSubAgentRepository durableSubAgentRepository(@Qualifier("dataSource") DataSource source,Clock clock){return new DurableSubAgentRepository(source,clock);}
  @Bean
  @ConditionalOnProperty(name="rei.subagents.dag-enabled",havingValue="true")
  SubAgentDagService subAgentDagService(SubAgentProperties properties,DurableSubAgentRepository repository,SubAgentRunner runner,
      SubAgentRegistry registry,ParallelSubAgentDelegator parallel,Clock clock){return new SubAgentDagService(properties,repository,runner,registry,parallel,clock);}
}
