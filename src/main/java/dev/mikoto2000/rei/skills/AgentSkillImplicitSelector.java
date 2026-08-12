package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;

@Component
public class AgentSkillImplicitSelector implements AgentSkillImplicitSelection {

  private static final Logger log = LoggerFactory.getLogger(AgentSkillImplicitSelector.class);
  private static final Pattern JSON_STRING = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");
  private static final int EXCERPT_LENGTH = 240;

  private final LlmModelProvider modelProvider;
  private final AgentSkillRepository repository;

  public AgentSkillImplicitSelector(ChatModel chatModel, AgentSkillRepository repository) {
    this(new FixedLlmModelProvider(chatModel), repository);
  }

  @Autowired
  public AgentSkillImplicitSelector(LlmModelProvider modelProvider, AgentSkillRepository repository) {
    this.modelProvider = modelProvider;
    this.repository = repository;
  }

  @Override
  public List<AgentSkill> select(String prompt, Set<String> excludedSkillNames) {
    List<AgentSkill> candidates = repository.findEnabled().stream()
        .filter(skill -> excludedSkillNames == null || !excludedSkillNames.contains(skill.name()))
        .toList();
    if (candidates.isEmpty()) {
      return List.of();
    }
    try {
      String content = modelProvider.chatModel(LlmFeature.AGENT_SKILLS).call(buildSelectionPrompt(prompt, candidates));
      return resolveSelectedSkills(parseJsonStringArray(content), candidates);
    } catch (Exception e) {
      log.debug("Agent Skill implicit selection failed", e);
      return List.of();
    }
  }

  private String buildSelectionPrompt(String userPrompt, List<AgentSkill> candidates) {
    StringBuilder builder = new StringBuilder();
    builder.append("""
        次のユーザー依頼に役立つ Agent Skill を選んでください。
        該当なしの場合は空配列を返してください。
        必ず JSON 配列のみを返してください。

        User request:
        """);
    builder.append(userPrompt == null ? "" : userPrompt).append("\n\nSkills:\n");
    for (AgentSkill skill : candidates) {
      builder.append("- name: ").append(skill.name()).append('\n');
      builder.append("  description: ").append(skill.description()).append('\n');
      builder.append("  excerpt: ").append(excerpt(skill.instructions())).append('\n');
    }
    builder.append("\nReturn format:\n[\"skill-name\"]\n");
    return builder.toString();
  }

  private String excerpt(String instructions) {
    String normalized = instructions == null ? "" : instructions.replaceAll("\\s+", " ").strip();
    if (normalized.length() <= EXCERPT_LENGTH) {
      return normalized;
    }
    return normalized.substring(0, EXCERPT_LENGTH);
  }

  private List<String> parseJsonStringArray(String content) {
    String text = content == null ? "" : content.strip();
    if (!text.startsWith("[") || !text.endsWith("]")) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    Matcher matcher = JSON_STRING.matcher(text);
    while (matcher.find()) {
      names.add(unescape(matcher.group(1)));
    }
    return names;
  }

  private String unescape(String value) {
    return value.replace("\\\"", "\"").replace("\\\\", "\\");
  }

  private List<AgentSkill> resolveSelectedSkills(List<String> names, List<AgentSkill> candidates) {
    Set<String> uniqueNames = new LinkedHashSet<>(names);
    List<AgentSkill> selected = new ArrayList<>();
    for (String name : uniqueNames) {
      candidates.stream()
          .filter(skill -> skill.name().equals(name))
          .findFirst()
          .ifPresent(selected::add);
    }
    return List.copyOf(selected);
  }

  private static class FixedLlmModelProvider extends LlmModelProvider {
    private final ChatModel chatModel;

    FixedLlmModelProvider(ChatModel chatModel) {
      super(chatModel, new dev.mikoto2000.rei.llm.LlmProperties());
      this.chatModel = chatModel;
    }

    @Override
    public ChatModel chatModel(String feature) {
      return chatModel;
    }
  }
}
