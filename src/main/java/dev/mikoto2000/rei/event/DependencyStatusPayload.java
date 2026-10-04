package dev.mikoto2000.rei.event;
/** Observed dependency facts, without response bodies, process logs or user answers. */
public record DependencyStatusPayload(String dependencyId,String kind,String state,String reason,long revision) implements AgentEventPayload {}
