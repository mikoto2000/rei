package dev.mikoto2000.rei.subagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 補助エージェントを登録して管理し、識別子から対象を参照できるようにする。
 *
 * <p>A failed candidate never replaces the live snapshot. Runs retain their resolved definition.
 */
public final class SubAgentRegistry {
  /** ディレクトリ。 */
  private final Path directory;

  /** 読み込みの窓口。 */
  private final SubAgentDefinitionLoader loader;

  /** 状態の記録。 */
  private volatile List<SubAgentDefinition> snapshot = List.of();

  /** 指定された値や利用先を使って、SubAgentRegistryを初期化する。 */
  public SubAgentRegistry(Path directory, SubAgentDefinitionLoader loader) {
    this.directory = directory.toAbsolutePath().normalize();
    this.loader = loader;
  }

  /** 補助エージェントへの委譲で用いるディレクトリを返す。 */
  public Path directory() {
    return directory;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<SubAgentDefinition> list() {
    return snapshot;
  }

  /** 対象を特定できるよう、別識別子を探す。 */
  public Optional<SubAgentDefinition> findById(String id) {
    return snapshot.stream().filter(d -> d.id().equals(id)).findFirst();
  }

  /** 変更された設定や記録を反映するため、補助エージェント登録台帳を読み直す。 */
  public synchronized List<String> reload() {
    List<String> errors = new ArrayList<>();
    Map<String, SubAgentDefinition> candidate = new TreeMap<>();
    if (Files.notExists(directory)) {
      snapshot = List.of();
      return List.of();
    }
    try (var files = Files.list(directory)) {
      for (Path file :
          files
              .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
              .sorted()
              .toList()) {
        try {
          var definition = loader.load(file);
          var previous = candidate.putIfAbsent(definition.id(), definition);
          if (previous != null)
            errors.add(
                file
                    + ": id: duplicate id "
                    + definition.id()
                    + " (also "
                    + previous.source()
                    + ")");
        } catch (IllegalArgumentException e) {
          errors.add(e.getMessage());
        }
      }
    } catch (java.io.IOException e) {
      errors.add(directory + ": cannot scan configuration directory");
    }
    if (errors.isEmpty()) snapshot = List.copyOf(candidate.values());
    return List.copyOf(errors);
  }
}
