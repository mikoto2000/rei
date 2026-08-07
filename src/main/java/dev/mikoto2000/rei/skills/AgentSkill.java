package dev.mikoto2000.rei.skills;

import java.nio.file.Path;

public record AgentSkill(
    String name,
    String description,
    boolean enabled,
    Path directory,
    Path skillFile,
    String instructions) {
}
