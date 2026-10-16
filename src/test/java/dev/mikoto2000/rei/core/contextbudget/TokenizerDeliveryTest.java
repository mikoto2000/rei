package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenizerDeliveryTest {
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

  @Test
  void giantWordsUseABoundedByteUpperEstimateInsteadOfUnboundedBpeWork() {
    assertThat(TokenEstimator.tokenizerAware().text("x".repeat(40000)))
        .isGreaterThanOrEqualTo(40000);
  }
}
