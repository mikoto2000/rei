package dev.mikoto2000.rei.event;

public record ContextInjectedPayload(String source, Integer itemCount, int contextCharacters)
    implements AgentEventPayload {
}
