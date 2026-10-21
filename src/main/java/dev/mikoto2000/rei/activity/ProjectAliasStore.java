package dev.mikoto2000.rei.activity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * プロジェクト別名の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Demand reload of one alias/group snapshot. Invalid edits retain the entire last good
 * configuration.
 */
public final class ProjectAliasStore implements java.util.function.Supplier<ProjectNameNormalizer> {
  /** ファイル。 */
  private final Path file;

  /** 現在。 */
  private SummaryThemeConfiguration current =
      new SummaryThemeConfiguration(
          new ProjectNameNormalizer(Map.of()), SummaryThemeGroups.empty());

  /** 前回。 */
  private String previous;

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ProjectAliasStore.class);

  /** 指定された値や利用先を使って、ProjectAliasStoreを初期化する。 */
  public ProjectAliasStore(Path file) {
    this.file = file;
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  @Override
  public ProjectNameNormalizer get() {
    return snapshot().projects();
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  public synchronized SummaryThemeConfiguration snapshot() {
    try {
      if (Files.exists(file) && Files.size(file) > 65536)
        throw new IllegalArgumentException("Summary configuration too large");
      String text = Files.exists(file) ? Files.readString(file) : "";
      if (Objects.equals(text, previous)) return current;
      previous = text;
      var options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(0);
      options.setCodePointLimit(65536);
      Object data = new Yaml(new SafeConstructor(options)).load(text);
      var aliases = new LinkedHashMap<String, List<String>>();
      Object groupData = null;
      if (data != null) {
        if (!(data instanceof Map<?, ?> root)
            || root.isEmpty()
            || !Set.of("projectAliases", "themeGroups").containsAll(root.keySet()))
          throw new IllegalArgumentException("Invalid summary configuration");
        if (root.containsKey("projectAliases")) {
          if (!(root.get("projectAliases") instanceof Map<?, ?> entries))
            throw new IllegalArgumentException("Invalid aliases");
          for (var e : entries.entrySet()) {
            if (!(e.getKey() instanceof String name)
                || !(e.getValue() instanceof List<?> values)
                || values.stream().anyMatch(v -> !(v instanceof String)))
              throw new IllegalArgumentException("Invalid alias values");
            aliases.put(name, values.stream().map(String.class::cast).toList());
          }
        }
        groupData = root.get("themeGroups");
        if (root.containsKey("themeGroups") && groupData == null)
          throw new IllegalArgumentException("Invalid groups");
      }
      var names = new ProjectNameNormalizer(aliases);
      current = new SummaryThemeConfiguration(names, SummaryThemeGroups.parse(groupData, names));
      log.debug(
          "[summary-theme] config file={} exists={} canonicalAndAliasEntries={} groups={}",
          file.toAbsolutePath().normalize(),
          Files.exists(file),
          names.aliasCount(),
          current.groups().groups());
    } catch (Exception error) {
      log.warn("Daily summary configuration unavailable; last valid alias/group snapshot retained");
    }
    return current;
  }
}
