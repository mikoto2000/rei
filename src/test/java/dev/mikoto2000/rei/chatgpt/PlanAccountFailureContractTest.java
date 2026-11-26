package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics;
import dev.mikoto2000.rei.toolcontract.ExternalReadFailure;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The existing account exception exposes safe host-defined diagnostics and never permits replay.
 */
class PlanAccountFailureContractTest {
  @ParameterizedTest
  @EnumSource(ChatGptException.Code.class)
  void accountFailuresUseTheSharedSafeFailureContractWithoutAutomaticRetry(
      ChatGptException.Code code) {
    var error = ChatGptException.of(code);
    assertThat(error).isInstanceOf(ExternalReadFailure.class);
    var reported = (ExternalReadFailure) (Object) error;
    String expected = "chatgpt_" + code.name().toLowerCase(Locale.ROOT);
    assertThat(reported.diagnosticCode()).isEqualTo(expected);
    assertThat(reported.diagnosticMessage()).isEqualTo(error.getMessage());
    assertThat(reported.retryAllowed()).isFalse();
    assertThat(error.getCause()).isNull();
    var diagnostic = FailureDiagnostics.classify(error);
    assertThat(diagnostic.code()).isEqualTo(expected);
    assertThat(diagnostic.message()).isEqualTo(error.getMessage());
  }

  @Test
  void unsafeProviderStringsCannotEnterTheCommonDiagnostic() {
    var error =
        ChatGptException.response(
            ChatGptException.Code.AUTH_INVALID,
            "responses",
            401,
            "remote-secret-type",
            12,
            65536,
            "remote-secret-code");
    var diagnostic = FailureDiagnostics.classify(error);
    assertThat(diagnostic.code()).isEqualTo("chatgpt_auth_invalid");
    assertThat(diagnostic.message())
        .contains("httpStatus=401", "contentType=unsupported")
        .doesNotContain("remote-secret-type", "remote-secret-code");
  }
}
