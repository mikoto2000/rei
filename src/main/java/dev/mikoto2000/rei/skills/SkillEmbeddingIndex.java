package dev.mikoto2000.rei.skills;

import java.util.List;
import java.util.Map;

/** Optional metadata-vector cache. Never stores queries, instructions or Skill objects. */
public interface SkillEmbeddingIndex {
  Map<String, float[]> load(String namespace, List<String> profiles);

  void replace(String namespace, Map<String, float[]> vectors);

  void invalidate(String namespace);
}
