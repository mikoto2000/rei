package dev.mikoto2000.rei.skills;

import java.nio.file.Path;
import java.util.List;

public record AgentSkill(
    String name,
    String description,
    List<String> keywords,
    boolean enabled,
    Path directory,
    Path skillFile,
    String instructions) {

  public AgentSkill {
    keywords = keywords == null ? List.of() : List.copyOf(keywords);
  }

  public AgentSkill(String name, String description, boolean enabled, Path directory, Path skillFile,
      String instructions) {
    this(name, description, List.of(), enabled, directory, skillFile, instructions);
  }
}
