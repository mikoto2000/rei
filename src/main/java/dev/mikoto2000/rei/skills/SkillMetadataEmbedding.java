package dev.mikoto2000.rei.skills;

import java.util.List;

@FunctionalInterface
public interface SkillMetadataEmbedding {
  /** One bounded batch, in input order. Metadata/query text only; never Skill instructions. */
  List<float[]> embed(List<String> texts);
}
