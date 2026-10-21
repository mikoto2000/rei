package dev.mikoto2000.rei.github;

/**
 * GitHubとの連携に必要なGit連携先状態確認の窓口を扱う。
 *
 * <p>Optional read-only API verification before accepting effects. Unavailable is never confirmed.
 */
@FunctionalInterface
public interface GitHubStateVerifier {
  /** GitHubとの連携における評価結果の選択肢。 */
  enum Verdict {
    /** 確認済み。 */
    CONFIRMED,
    /** 拒否済み。 */
    REJECTED,
    /** 利用不可。 */
    UNAVAILABLE
  }

  /** 要求された条件を満たすことを確認する。 */
  Verdict verify(GitHubFact fact);
}
