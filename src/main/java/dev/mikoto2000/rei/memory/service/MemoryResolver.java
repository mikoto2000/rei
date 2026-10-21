package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.LongTermMemory;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 記憶の指定を解決し、利用する対象を確定する。 */
@Service
public class MemoryResolver {
  /** モデル。 */
  private final MemoryResolutionModel model;

  /** この機能の動作設定。 */
  private final MemoryProperties properties;

  /** 指定された値や利用先を使って、MemoryResolverを初期化する。 */
  public MemoryResolver(MemoryResolutionModel model, MemoryProperties properties) {
    this.model = model;
    this.properties = properties;
  }

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public MemoryResolution resolve(
      MemoryCandidate c, List<LongTermMemory> memories, String project) {
    return resolve(c, memories, project, null);
  }

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public MemoryResolution resolve(
      MemoryCandidate c,
      List<LongTermMemory> memories,
      String project,
      dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    if (c.confidence() < properties.sleep().minConfidence()
        || c.importance() < properties.sleep().minImportance())
      return new MemoryResolution(MemoryAction.IGNORE, List.of());
    var existing =
        memories.stream()
            .filter(
                m ->
                    m.status() == MemoryStatus.ACTIVE
                        && m.scope() == c.scope()
                        && (c.scope() == MemoryScope.GLOBAL
                            || Objects.equals(project, m.projectId())))
            .toList();
    var exact =
        existing.stream()
            .filter(m -> normalize(m.content()).equals(normalize(c.content())))
            .findFirst();
    if (exact.isPresent())
      return new MemoryResolution(MemoryAction.DUPLICATE, List.of(exact.get().id()));
    if (existing.isEmpty()) return new MemoryResolution(MemoryAction.NEW, List.of());
    var result = budget == null ? model.resolve(c, existing) : model.resolve(c, existing, budget);
    var ids = existing.stream().map(LongTermMemory::id).toList();
    int count = result.targetIds().size();
    if (!ids.containsAll(result.targetIds()) || new HashSet<>(result.targetIds()).size() != count)
      throw new IllegalArgumentException("Resolution references unavailable memory");
    boolean valid =
        switch (result.action()) {
          case NEW, IGNORE -> count == 0;
          case DUPLICATE, UPDATE, SUPERSEDE -> count == 1;
          case MERGE -> count >= 2;
          case CONFLICT -> count >= 1;
        };
    if (!valid) throw new IllegalArgumentException("Resolution target count invalid");
    return result;
  }

  /** 同じ意味の指定を共通に扱えるよう、表記や形式をそろえる。 */
  static String normalize(String text) {
    return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
