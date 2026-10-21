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

/** ファイルシステムエージェントスキルを保存・検索するための窓口。 */
@Repository
public class FileSystemAgentSkillRepository implements AgentSkillRepository {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(FileSystemAgentSkillRepository.class);

  /** スキルファイル名。 */
  private static final String SKILL_FILE_NAME = "SKILL.md";

  /** この機能の動作設定。 */
  private final AgentSkillsProperties properties;

  /** キャッシュ。 */
  private volatile List<AgentSkill> cache;

  /** 指定された値や利用先を使って、FileSystemAgentSkillRepositoryを初期化する。 */
  public FileSystemAgentSkillRepository(AgentSkillsProperties properties) {
    this.properties = properties;
    this.cache = loadSkills();
  }

  /** 対象を特定できるよう、全対象を探す。 */
  @Override
  public List<AgentSkill> findAll() {
    return cache;
  }

  /** 対象を特定できるよう、有効状態を探す。 */
  @Override
  public List<AgentSkill> findEnabled() {
    return cache.stream().filter(AgentSkill::enabled).toList();
  }

  /** 対象を特定できるよう、別名前を探す。 */
  @Override
  public Optional<AgentSkill> findByName(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    return cache.stream().filter(skill -> skill.name().equals(name)).findFirst();
  }

  /** 変更された設定や記録を反映するため、ファイルシステムエージェントスキル保存と検索の窓口を読み直す。 */
  @Override
  public void reload() {
    this.cache = loadSkills();
  }

  /** 保存された情報を利用できるよう、スキル一覧を読み込む。 */
  private List<AgentSkill> loadSkills() {
    Map<String, AgentSkill> skills = new LinkedHashMap<>();
    for (String directory : properties.getDirectories()) {
      Path skillsDirectory = resolveDirectory(directory);
      if (isKiroPath(skillsDirectory) || !Files.isDirectory(skillsDirectory)) {
        continue;
      }
      try (var stream = Files.list(skillsDirectory)) {
        stream
            .filter(Files::isDirectory)
            .map(path -> path.resolve(SKILL_FILE_NAME))
            .filter(Files::isRegularFile)
            .forEach(
                skillFile ->
                    loadSkill(skillFile)
                        .ifPresent(skill -> skills.putIfAbsent(skill.name(), skill)));
      } catch (IOException e) {
        log.warn("Failed to scan Agent Skills directory: {}", skillsDirectory, e);
      }
    }
    return List.copyOf(skills.values());
  }

  /** 保存された情報を利用できるよう、スキルを読み込む。 */
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
      return Optional.of(
          new AgentSkill(
              name,
              description,
              parsed.keywords(),
              enabled,
              skillFile.getParent(),
              skillFile,
              parsed.instructions()));
    } catch (Exception e) {
      log.warn("Failed to load Agent Skill: {}", skillFile, e);
      return Optional.empty();
    }
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
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

  /** 入力を機能内で扱えるよう、FrontMatterを解釈する。 */
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

  /** スキルの利用において、Indentedかどうかを判定する。 */
  private boolean isIndented(String line) {
    return line.startsWith(" ") || line.startsWith("\t");
  }

  /** 既存の記録へ経緯を残すため、まとまり行を追記する。 */
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

  /** スキルの利用で用いる引用の解除を返す。 */
  private String unquote(String value) {
    if ((value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'"))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  /** 指定や設定に応じた対象を利用するため、ディレクトリを確定する。 */
  private Path resolveDirectory(String directory) {
    String resolved =
        directory == null ? "" : directory.replace("${user.dir}", System.getProperty("user.dir"));
    return Path.of(resolved).toAbsolutePath().normalize();
  }

  /** スキルの利用において、Kiroパスかどうかを判定する。 */
  private boolean isKiroPath(Path path) {
    List<String> names = new ArrayList<>();
    for (Path part : path) {
      names.add(part.toString());
    }
    return names.contains(".kiro");
  }

  /**
   * スキルの利用で受け渡す解釈済み付随情報を表す。
   *
   * @param values 値一覧
   * @param keywords 検索語句一覧
   */
  private record ParsedMetadata(Map<String, String> values, List<String> keywords) {}

  /**
   * スキルの利用で受け渡す解釈済みスキルを表す。
   *
   * @param metadata 付随情報
   * @param keywords 検索語句一覧
   * @param instructions 指示一覧
   */
  private record ParsedSkill(
      Map<String, String> metadata, List<String> keywords, String instructions) {}
}
