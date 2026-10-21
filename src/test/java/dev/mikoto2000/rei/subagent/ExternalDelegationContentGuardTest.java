package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 外部委譲内容利用条件の確認の結果と状態の契約を検証するテスト。 */
class ExternalDelegationContentGuardTest {
  /** 補助エージェントへの委譲で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"password\":{\"value\":\"opaque\"}}",
        "{\"token\":[\"opaque\"]}",
        "{\"result\":{\"client_secret\":{\"example\":\"opaque\"}}}",
        "{\"pass\\u0077ord\":\"opaque\"}",
        "{\"quote\":\"pass\\u0077ord=opaque\"}"
      })
  void decodedAndStructuredCredentialValuesRemainRestricted(String content) {
    assertThat(ExternalDelegationContentGuard.hasUnmaskedCredentials(content)).isTrue();
  }

  /** 補助エージェントへの委譲で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void quotedMaskedReferencesAndOrdinaryStructuredResultsAreAccepted() {
    String content =
        "{\"references\":[{\"quote\":\"spring.datasource.password=\\\"[REDACTED]\\\"\"}],"
            + "\"result\":{\"findings\":[],\"unverified\":[]}}";
    assertThat(ExternalDelegationContentGuard.hasUnmaskedCredentials(content)).isFalse();
  }

  /** 補助エージェントへの委譲で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void malformedJsonStillChecksVisibleCredentialsWithoutTryingToRepairTheReply() {
    assertThat(
            ExternalDelegationContentGuard.hasUnmaskedCredentials("invalid JSON password=opaque"))
        .isTrue();
    assertThat(
            ExternalDelegationContentGuard.hasUnmaskedCredentials(
                "invalid JSON password=[REDACTED]"))
        .isFalse();
  }
}
