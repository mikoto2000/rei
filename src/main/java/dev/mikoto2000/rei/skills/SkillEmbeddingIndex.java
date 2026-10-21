package dev.mikoto2000.rei.skills;

import java.util.List;
import java.util.Map;

/**
 * スキルの利用に必要なスキル埋め込み表現位置を扱う。
 *
 * <p>Optional metadata-vector cache. Never stores queries, instructions or Skill objects.
 */
public interface SkillEmbeddingIndex {
  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  Map<String, float[]> load(String namespace, List<String> profiles);

  /** 指定された内容へ切り替えるため、スキル埋め込み表現位置を置き換える。 */
  void replace(String namespace, Map<String, float[]> vectors);

  /** スキルの利用に関するキャッシュの無効化を処理する。 */
  void invalidate(String namespace);
}
