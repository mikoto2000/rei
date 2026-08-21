package dev.mikoto2000.rei.core.relatedgraph;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 実際のツール実行やコード解析で確認できたファイル間の関係を保持する。
 *
 * <p>LLM の推測だけで関係を作らず、検索結果や import 解析などで確認できた関係のみを登録する。
 * ファイル変更・削除時には、そのファイルを source または target とする関係を削除する。</p>
 */
public class RelatedFileGraph {

  static final int DEFAULT_MAX_RELATIONS = 200;

  /** 参照関係。 */
  public static final String TYPE_REFERENCES = "REFERENCES";
  /** import 関係。 */
  public static final String TYPE_IMPORTS = "IMPORTS";
  /** テスト関係。 */
  public static final String TYPE_TESTS = "TESTS";
  /** その他の関連。 */
  public static final String TYPE_RELATED = "RELATED";

  /** 検索結果から確認された関係。 */
  public static final String EVIDENCE_SEARCH = "SEARCH";
  /** import 解析から確認された関係。 */
  public static final String EVIDENCE_IMPORT = "IMPORT";
  /** 明示的なツール結果から確認された関係。 */
  public static final String EVIDENCE_EXPLICIT_TOOL_RESULT = "EXPLICIT_TOOL_RESULT";

  private final int maxRelations;
  private final Clock clock;
  private final Map<String, FileRelation> relations = new LinkedHashMap<>();

  public RelatedFileGraph() {
    this(DEFAULT_MAX_RELATIONS, Clock.systemDefaultZone());
  }

  public RelatedFileGraph(int maxRelations, Clock clock) {
    this.maxRelations = Math.max(1, maxRelations);
    this.clock = clock;
  }

  /** 現在の relation 一覧。 */
  public List<FileRelation> relations() {
    return List.copyOf(relations.values());
  }

  /** graph が空かどうか。 */
  public boolean isEmpty() {
    return relations.isEmpty();
  }

  /** 現在の relation 数。 */
  public int size() {
    return relations.size();
  }

  /** 最大 relation 数。 */
  public int maxRelations() {
    return maxRelations;
  }

  /** 関係を登録する。同一関係は重複登録せず lastConfirmedAt のみ更新する。 */
  public void addRelation(String sourcePath, String targetPath, String type, String evidence) {
    String key = key(sourcePath, targetPath, type);
    FileRelation existing = relations.get(key);
    if (existing != null) {
      relations.put(key, new FileRelation(existing.sourcePath(), existing.targetPath(), existing.type(),
          existing.evidence(), now()));
      return;
    }
    relations.put(key, new FileRelation(sourcePath, targetPath, type, evidence, now()));
    evictIfNeeded();
  }

  /** 指定パスに関係する relation を返す。 */
  public List<FileRelation> getRelated(String path) {
    return relations.values().stream()
        .filter(r -> r.sourcePath().equals(path) || r.targetPath().equals(path))
        .toList();
  }

  /** 指定パスを source または target とする relation を削除する。 */
  public void removeRelationsFor(String path) {
    relations.entrySet().removeIf(e -> e.getValue().sourcePath().equals(path)
        || e.getValue().targetPath().equals(path));
  }

  /** graph 全体をクリアする。 */
  public void clear() {
    relations.clear();
  }

  /**
   * LLM コンテキストに渡す表現を組み立てる。
   *
   * <p>workingSetPaths に含まれるファイルを source とする relation のみを提示する。
   * 全 graph を毎ターン渡さない。</p>
   */
  public String renderForPrompt(java.util.Set<String> workingSetPaths) {
    StringBuilder sb = new StringBuilder();
    for (FileRelation relation : relations.values()) {
      if (!workingSetPaths.contains(relation.sourcePath())) {
        continue;
      }
      sb.append("- ").append(relation.sourcePath()).append(" [").append(relation.type().toLowerCase()).append("] ");
      sb.append(relation.targetPath()).append(" (evidence: ").append(relation.evidence()).append(")\n");
    }
    return sb.toString();
  }

  private void evictIfNeeded() {
    while (relations.size() > maxRelations) {
      String oldest = relations.values().stream()
          .min(Comparator.comparing(FileRelation::lastConfirmedAt))
          .map(FileRelation::key)
          .orElse(null);
      if (oldest == null) {
        return;
      }
      relations.remove(oldest);
    }
  }

  private String key(String sourcePath, String targetPath, String type) {
    return sourcePath + "\u0001" + targetPath + "\u0001" + type;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }
}
