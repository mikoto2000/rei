package dev.mikoto2000.rei.skills;

import java.util.*;

/** Optional metadata-vector cache. Never stores queries, instructions or Skill objects. */
public interface SkillEmbeddingIndex {
  Map<String,float[]> load(String namespace,List<String> profiles);
  void replace(String namespace,Map<String,float[]> vectors);
  void invalidate(String namespace);
}
