package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

/** 検証条件を再現するためのLiveE2E判断基準。 */
final class LiveE2EPolicy {
  /** {@code enabled} の条件を再現するための補助情報を返す。 */
  static boolean enabled(Map<String, String> environment, String key) {
    return "true".equalsIgnoreCase(environment.get(key));
  }

  /** 検証用に、条件を満たさない操作を拒否するため、LiveE2E判断基準の前提条件を確認する。 */
  static void require(String flag) {
    assumeTrue(enabled(System.getenv(), flag), "LIVE_DISABLED: set " + flag + "=true explicitly");
  }

  /** {@code account} の条件を再現するため、検証用の操作や状態を扱う。 */
  static void account(String provider) {
    require("REI_E2E_" + provider);
    assumeTrue(
        enabled(System.getenv(), "REI_E2E_" + provider + "_ACCOUNT_READY"),
        "ACCOUNT_NOT_CONFIRMED: attest existing subscription login with REI_E2E_"
            + provider
            + "_ACCOUNT_READY=true");
  }

  /** {@code success} の条件を再現するため、検証用の操作や状態を扱う。 */
  static void success(ExternalAgentResult result) {
    assumeTrue(
        result.status() != ExternalAgentResult.Status.UNAVAILABLE,
        "CLI_OR_CAPABILITY_UNAVAILABLE: live adapter unavailable");
    assumeTrue(
        !(result.status() == ExternalAgentResult.Status.REJECTED
            && result.summary().startsWith("Claude Code subscription login required")),
        "SUBSCRIPTION_LOGIN_UNAVAILABLE: authenticate outside this test");
    org.junit.jupiter.api.Assertions.assertTrue(
        result.success(), "Live adapter outcome: " + result.status());
  }
}
