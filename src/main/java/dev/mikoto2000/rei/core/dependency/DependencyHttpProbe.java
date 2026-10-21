package dev.mikoto2000.rei.core.dependency;

/**
 * 外部条件の待機に必要な依存条件HTTP通信利用可否の確認を扱う。
 *
 * <p>Read-only network observation port. Authorization belongs to the caller.
 */
@FunctionalInterface
public interface DependencyHttpProbe {
  /** 外部条件の待機で用いる利用可否の確認を返す。 */
  DependencyObservation probe(String id, String url, int expectedStatus);

  /** 外部条件の待機で用いる利用可否の確認本文を返す。 */
  default DependencyObservation probeBody(
      String id, String url, int expectedStatus, String sha256) {
    return new DependencyObservation(
        id, DependencyState.BLOCKED, "http_body_verification_unsupported");
  }

  /** 外部条件の待機で用いる利用可否の確認JSON変換を返す。 */
  default DependencyObservation probeJson(String id, String url, String expected) {
    return new DependencyObservation(
        id, DependencyState.BLOCKED, "http_json_verification_unsupported");
  }
}
