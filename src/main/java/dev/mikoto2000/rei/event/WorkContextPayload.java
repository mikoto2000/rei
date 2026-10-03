package dev.mikoto2000.rei.event;
/** No project text or tool output is emitted; the source run is a reference, not a new execution. */
public record WorkContextPayload(String sourceRunId,Long revision,String status) implements AgentEventPayload {}
