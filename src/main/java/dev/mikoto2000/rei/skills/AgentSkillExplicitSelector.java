package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class AgentSkillExplicitSelector {

  private static final Pattern SKILL_TOKEN = Pattern.compile("@skill:([A-Za-z0-9._-]+)");

  private final AgentSkillRepository repository;

  public AgentSkillExplicitSelector(AgentSkillRepository repository) {
    this.repository = repository;
  }

  public ExplicitSelection select(String prompt) {
    String source = prompt == null ? "" : prompt;
    Matcher matcher = SKILL_TOKEN.matcher(source);
    List<AgentSkill> skills = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    while (matcher.find()) {
      String name = matcher.group(1);
      repository.findByName(name).ifPresentOrElse(skill -> {
        if (skill.enabled()) {
          skills.add(skill);
        } else {
          warnings.add("[warn] Skill は無効です: " + name);
        }
      }, () -> warnings.add("[warn] Skill が見つかりません: " + name));
    }
    String sanitizedPrompt = matcher.replaceAll("").replaceAll("\\s+", " ").strip();
    return new ExplicitSelection(List.copyOf(skills), List.copyOf(warnings), sanitizedPrompt);
  }

  public record ExplicitSelection(List<AgentSkill> skills, List<String> warnings, String sanitizedPrompt) {
  }
}
