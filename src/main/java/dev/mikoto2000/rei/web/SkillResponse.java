package dev.mikoto2000.rei.web;
public record SkillResponse(String name, String description, boolean enabled, String instructions) {
  public static SkillResponse from(dev.mikoto2000.rei.skills.AgentSkill s) { return new SkillResponse(s.name(), s.description(), s.enabled(), s.instructions()); }
}
