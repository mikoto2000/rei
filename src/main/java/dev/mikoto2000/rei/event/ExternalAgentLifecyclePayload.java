package dev.mikoto2000.rei.event;

/** Operational metadata only; no request text, findings or process logs. */
public record ExternalAgentLifecyclePayload(String delegationId, String agent, String action, String status,
    String summary, long duration, Integer exitCode) implements AgentEventPayload {}
