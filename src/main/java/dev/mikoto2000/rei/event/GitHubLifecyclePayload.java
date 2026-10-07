package dev.mikoto2000.rei.event;
import dev.mikoto2000.rei.github.GitHubFact;
public record GitHubLifecyclePayload(String deliveryId,String sourceId,GitHubFact fact) implements AgentEventPayload {}
