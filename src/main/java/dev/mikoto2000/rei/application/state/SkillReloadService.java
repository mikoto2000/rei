package dev.mikoto2000.rei.application.state;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
/** Only reloads the server-configured skill catalog; accepts no path and writes no skill files. */
public record SkillReloadService(AgentSkillRepository repository) {
  public int reload() { repository.reload(); return repository.findAll().size(); }
}
