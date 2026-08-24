package dev.mikoto2000.rei.event;

import java.util.List;

/** Skill routing の正常終了と内訳。duration はすべて monotonic clock によるミリ秒値。 */
public record SkillRoutingCompletedPayload(
    long durationMs,
    int candidateCount,
    String selectedSkill,
    int routingInvocation,
    Long selectorDurationMs,
    Long metadataLoadDurationMs,
    Long skillLoadDurationMs,
    List<String> explicitSkillNames,
    List<String> implicitSkillNames,
    List<String> warnings) implements AgentEventPayload {

  public SkillRoutingCompletedPayload {
    explicitSkillNames = explicitSkillNames == null ? List.of() : List.copyOf(explicitSkillNames);
    implicitSkillNames = implicitSkillNames == null ? List.of() : List.copyOf(implicitSkillNames);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
