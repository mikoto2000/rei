package dev.mikoto2000.rei.skills;

import java.util.List;

/** スキルの利用に必要なスキル付随情報埋め込み表現を扱う。 */
@FunctionalInterface
public interface SkillMetadataEmbedding {
  /**
   * スキルの利用で用いる埋め込み表現の生成を返す。
   *
   * <p>One bounded batch, in input order. Metadata/query text only; never Skill instructions.
   */
  List<float[]> embed(List<String> texts);
}
