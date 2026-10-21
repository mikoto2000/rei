package dev.mikoto2000.rei.vectorstore;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sparseを受け渡し先が扱える形式へ整える。
 *
 * <p>Optional learned sparse provider boundary. Numeric vocabulary IDs never imply BM25 term
 * weights.
 */
public interface SparseEncoder {
  /** 関連文書の検索で用いるモデルの識別子を返す。 */
  String modelId();

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  Vector encode(String text, Runnable checkActive);

  /**
   * 関連文書の検索で受け渡すベクトルを表す。
   *
   * @param modelId モデルの識別子
   * @param dimensions 次元数
   * @param weights 重み一覧
   */
  record Vector(String modelId, int dimensions, Map<Integer, Double> weights) {
    /** 受け取った情報からVectorを作成する。 */
    public Vector {
      if (modelId == null
          || !modelId.matches("[A-Za-z0-9._:/-]{1,128}")
          || dimensions < 1
          || dimensions > 1048576
          || weights == null
          || weights.size() > 4096)
        throw new IllegalArgumentException("Bounded versioned sparse vector required");
      for (var entry : weights.entrySet())
        if (entry.getKey() == null
            || entry.getKey() < 0
            || entry.getKey() >= dimensions
            || entry.getValue() == null
            || !Double.isFinite(entry.getValue())
            || entry.getValue() <= 0
            || entry.getValue() > 1000000)
          throw new IllegalArgumentException("Invalid sparse vocabulary ID or weight");
      weights = Collections.unmodifiableMap(new TreeMap<>(weights));
    }
  }
}
