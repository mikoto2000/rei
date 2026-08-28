package dev.mikoto2000.rei.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

@Repository
public class FileSystemAgentSkillRepository implements AgentSkillRepository {

  private static final Logger log = LoggerFactory.getLogger(FileSystemAgentSkillRepository.class);
  private static final String SKILL_FILE_NAME = "SKILL.md";

  private final AgentSkillsProperties properties;
  private volatile List<AgentSkill> cache;

  public FileSystemAgentSkillRepository(AgentSkillsProperties properties) {
    this.properties = properties;
    this.cache = loadSkills();
  }

  @Override
  public List<AgentSkill> findAll() {
    return cache;
  }

  @Override
  public List<AgentSkill> findEnabled() {
    return cache.stream().filter(AgentSkill::enabled).toList();
  }

  @Override
  public Optional<AgentSkill> findByName(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    return cache.stream().filter(skill -> skill.name().equals(name)).findFirst();
  }

  @Override
  public void reload() {
    this.cache = loadSkills();
  }

  private List<AgentSkill> loadSkills() {
    Map<String, AgentSkill> skills = new LinkedHashMap<>();
    for (String directory : properties.getDirectories()) {
      Path skillsDirectory = resolveDirectory(directory);
      if (isKiroPath(skillsDirectory) || !Files.isDirectory(skillsDirectory)) {
        continue;
      }
      try (var stream = Files.list(skillsDirectory)) {
        stream.filter(Files::isDirectory)
            .map(path -> path.resolve(SKILL_FILE_NAME))
            .filter(Files::isRegularFile)
            .forEach(skillFile -> loadSkill(skillFile).ifPresent(skill -> skills.putIfAbsent(skill.name(), skill)));
      } catch (IOException e) {
        log.warn("Failed to scan Agent Skills directory: {}", skillsDirectory, e);
      }
    }
    return List.copyOf(skills.values());
  }

  private Optional<AgentSkill> loadSkill(Path skillFile) {
    try {
      String content = Files.readString(skillFile);
      ParsedSkill parsed = parse(content);
      String name = parsed.metadata().get("name");
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Skill name is required");
      }
      String description = parsed.metadata().getOrDefault("description", "");
      boolean enabled = Boolean.parseBoolean(parsed.metadata().getOrDefault("enabled", "true"));
      return Optional.of(new AgentSkill(name, description, parsed.keywords(), enabled, skillFile.getParent(), skillFile,
          parsed.instructions()));
    } catch (Exception e) {
      log.warn("Failed to load Agent Skill: {}", skillFile, e);
      return Optional.empty();
    }
  }

  private ParsedSkill parse(String content) {
    String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
    if (!normalized.startsWith("---\n")) {
      throw new IllegalArgumentException("YAML front matter is required");
    }
    int end = normalized.indexOf("\n---", 4);
    if (end < 0) {
      throw new IllegalArgumentException("YAML front matter is not closed");
    }
    String frontMatter = normalized.substring(4, end);
    String instructions = normalized.substring(end + "\n---".length()).stripLeading();
    ParsedMetadata metadata = parseFrontMatter(frontMatter);
    return new ParsedSkill(metadata.values(), metadata.keywords(), instructions);
  }

  private ParsedMetadata parseFrontMatter(String frontMatter) {
    Map<String, String> metadata = new LinkedHashMap<>();
    List<String> keywords = new ArrayList<>();
    String blockKey = null;
    String blockStyle = null;
    StringBuilder blockValue = new StringBuilder();
    for (String line : frontMatter.split("\n", -1)) {
      if (line.isBlank()) {
        if (blockKey != null && "|".equals(blockStyle)) {
          blockValue.append('\n');
        }
        continue;
      }
      if (blockKey != null && isIndented(line)) {
        if ("keywords".equals(blockKey) && line.strip().startsWith("-")) {
          String keyword = unquote(line.strip().substring(1).strip());
          if (!keyword.isBlank()) keywords.add(keyword);
          continue;
        }
        appendBlockLine(blockValue, blockStyle, line.strip());
        continue;
      }
      if (blockKey != null) {
        metadata.put(blockKey, blockValue.toString().stripTrailing());
        blockKey = null;
        blockStyle = null;
        blockValue = new StringBuilder();
      }
      int separator = line.indexOf(':');
      if (separator <= 0) {
        throw new IllegalArgumentException("Invalid front matter line: " + line);
      }
      String key = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
      String value = line.substring(separator + 1).strip();
      if ("keywords".equals(key) && value.isEmpty()) {
        blockKey = key;
        blockStyle = "list";
      } else if (value.equals(">") || value.equals("|")) {
        blockKey = key;
        blockStyle = value;
      } else {
        metadata.put(key, unquote(value));
      }
    }
    if (blockKey != null) {
      metadata.put(blockKey, blockValue.toString().stripTrailing());
    }
    return new ParsedMetadata(metadata, List.copyOf(keywords));
  }

  private boolean isIndented(String line) {
    return line.startsWith(" ") || line.startsWith("\t");
  }

  private void appendBlockLine(StringBuilder blockValue, String blockStyle, String line) {
    if ("|".equals(blockStyle)) {
      if (!blockValue.isEmpty()) {
        blockValue.append('\n');
      }
      blockValue.append(line);
      return;
    }
    if (!blockValue.isEmpty()) {
      blockValue.append(' ');
    }
    blockValue.append(line);
  }

  private String unquote(String value) {
    if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private Path resolveDirectory(String directory) {
    String resolved = directory == null ? "" : directory.replace("${user.dir}", System.getProperty("user.dir"));
    return Path.of(resolved).toAbsolutePath().normalize();
  }

  private boolean isKiroPath(Path path) {
    List<String> names = new ArrayList<>();
    for (Path part : path) {
      names.add(part.toString());
    }
    return names.contains(".kiro");
  }

  private record ParsedMetadata(Map<String, String> values, List<String> keywords) {
  }

  private record ParsedSkill(Map<String, String> metadata, List<String> keywords, String instructions) {
  }
}
