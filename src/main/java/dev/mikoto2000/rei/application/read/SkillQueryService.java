package dev.mikoto2000.rei.application.read;
import dev.mikoto2000.rei.skills.*;
import java.util.*;
/** Shared shell/Web read boundary; deliberately has no reload or filesystem operation. */
public record SkillQueryService(AgentSkillRepository repository) {
  public List<AgentSkill> list() { return repository.findAll(); }
  public Optional<AgentSkill> find(String name) { return repository.findByName(name); }
}
