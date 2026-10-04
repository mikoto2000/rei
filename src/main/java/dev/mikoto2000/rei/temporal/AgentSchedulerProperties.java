package dev.mikoto2000.rei.temporal;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("rei.agent-scheduler")
public record AgentSchedulerProperties(boolean enabled) {}
