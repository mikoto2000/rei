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
}
