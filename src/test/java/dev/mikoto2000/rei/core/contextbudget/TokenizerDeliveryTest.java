package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 語句の分割配信の結果と状態の契約を検証するテスト。 */
class TokenizerDeliveryTest {
  /** 上限を超える対応済み語句の分割推定値を利用することを確認する。 */
  @Test
  void escapingAndSupplementaryUnicodeUseTheLargerSupportedTokenizerEstimate() {
    var estimator = TokenEstimator.tokenizerAware();
    String content = "\\\"🙂\n".repeat(100);
    var registry = com.knuddels.jtokkit.Encodings.newDefaultEncodingRegistry();
    int actual =
        registry
            .getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE)
            .countTokensOrdinary(content);
    assertThat(estimator.text(content)).isGreaterThanOrEqualTo(actual);
    assertThat(estimator.text(content))
        .isGreaterThanOrEqualTo(TokenEstimator.conservative().text(content));
  }

  /** 上限内に収めたバイト大文字推定値を利用することを確認する。 */
  @Test
  void giantWordsUseABoundedByteUpperEstimateInsteadOfUnboundedBpeWork() {
    assertThat(TokenEstimator.tokenizerAware().text("x".repeat(40000)))
        .isGreaterThanOrEqualTo(40000);
  }
}
