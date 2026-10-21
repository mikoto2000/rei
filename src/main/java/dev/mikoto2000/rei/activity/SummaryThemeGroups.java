package dev.mikoto2000.rei.activity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作業状況の把握に必要な要約テーマグループ一覧を扱う。
 *
 * <p>Explicit summary display groups; never infers project identity or topic association.
 */
public final class SummaryThemeGroups {
  /**
   * 作業状況の把握で受け渡すグループを表す。
   *
   * @param id 識別子
   * @param displayName 表示名前
   * @param projects プロジェクト一覧
   * @param themes テーマ一覧
   * @param allowSingleProject 許可単一プロジェクト
   */
  public record Group(
      String id,
      String displayName,
      Set<String> projects,
      Set<String> themes,
      boolean allowSingleProject) {}

  /** グループ一覧。 */
  private final List<Group> groups;

  /** 指定された値や利用先を使って、SummaryThemeGroupsを初期化する。 */
  private SummaryThemeGroups(List<Group> groups) {
    this.groups = List.copyOf(groups);
  }

  /** 対象がない場合の結果を表す。 */
  public static SummaryThemeGroups empty() {
    return new SummaryThemeGroups(List.of());
  }

  /** 作業状況の把握で用いるグループ一覧を返す。 */
  public List<Group> groups() {
    return groups;
  }

  /** 作業状況の把握で用いる識別子一覧対象を返す。 */
  List<String> idsFor(String project, List<String> topics) {
    return groups.stream()
        .filter(
            g ->
                !project.isBlank()
                    ? g.projects().contains(project)
                    : topics.stream().anyMatch(g.themes()::contains))
        .map(Group::id)
        .sorted()
        .toList();
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  static SummaryThemeGroups parse(Object data, ProjectNameNormalizer names) {
    if (data == null) return empty();
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (!(data instanceof Map<?, ?> entries) || entries.size() > 50)
      throw new IllegalArgumentException("Invalid theme groups");
    var result = new ArrayList<Group>();
    var projects = new HashSet<String>();
    var themes = new HashSet<String>();
    var displays = new HashSet<String>();
    for (var entry : entries.entrySet()) {
      if (!(entry.getKey() instanceof String id)
          || !id.matches("[a-z][a-z0-9-]{0,59}")
          || !(entry.getValue() instanceof Map<?, ?> fields)
          || !Set.of("displayName", "projects", "themes", "allowSingleProject")
              .containsAll(fields.keySet()))
        throw new IllegalArgumentException("Invalid theme group fields");
      Object display = fields.get("displayName");
      if (!(display instanceof String label)
          || label.isBlank()
          || label.length() > 60
          || label.chars().anyMatch(Character::isISOControl)
          || !displays.add(ActivityRolePolicy.normalize(label)))
        throw new IllegalArgumentException("Invalid group display name");
      var members = new LinkedHashSet<String>();
      for (var raw : strings(fields.get("projects"), 100)) {
        String canonical = names.project(raw);
        if (canonical.isBlank()) throw new IllegalArgumentException("Invalid group project");
        members.add(canonical);
      }
      if (members.isEmpty()) throw new IllegalArgumentException("Empty group projects");
      for (var member : members)
        if (!projects.add(member))
          throw new IllegalArgumentException("Project assigned to multiple groups");
      var topics = new LinkedHashSet<String>();
      for (var topic : strings(fields.get("themes"), 20)) {
        if (!WorkThemeAggregation.knownTopic(topic) || !topics.add(topic) || !themes.add(topic))
          throw new IllegalArgumentException("Invalid or conflicting group theme");
      }
      var single = fields.get("allowSingleProject");
      if (single != null && !(single instanceof Boolean))
        throw new IllegalArgumentException("Invalid single-project option");
      result.add(
          new Group(
              id,
              label.strip(),
              Set.copyOf(members),
              Set.copyOf(topics),
              Boolean.TRUE.equals(single)));
    }
    return new SummaryThemeGroups(result);
  }

  /** 作業状況の把握で用いる文字列一覧を返す。 */
  private static List<String> strings(Object value, int max) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list)
        || list.size() > max
        || list.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank() || s.length() > 60))
      throw new IllegalArgumentException("Invalid group list");
    return list.stream().map(String.class::cast).toList();
  }
}
