package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.chatgpt.ChatGptException;
import dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** SDK wrappers must retain only the existing safe account diagnostic, never provider text. */
class ResponsesAccountFailureBoundaryTest {
  @ParameterizedTest
  @EnumSource(ChatGptException.Code.class)
  void accountFailuresSurviveSdkWrappingWithoutRetryOrOriginalCauses(ChatGptException.Code code) {
    var account = ChatGptException.of(code);
    for (var original :
        java.util.List.of(account, new IllegalStateException("PRIVATE SDK", account))) {
      var normalized = ResponsesTransportException.normalize(original);
      assertThat(normalized.diagnosticCode()).isEqualTo(account.diagnosticCode());
      assertThat(normalized.diagnosticMessage()).isEqualTo(account.diagnosticMessage());
      assertThat(normalized.retryAllowed()).isFalse();
      assertThat(normalized.getCause()).isNull();
      assertThat(normalized.getMessage()).doesNotContain("PRIVATE");
      var diagnostic = FailureDiagnostics.classify(normalized);
      assertThat(diagnostic.code()).isEqualTo(account.diagnosticCode());
      assertThat(diagnostic.message()).isEqualTo(account.diagnosticMessage());
      assertThat(ResponsesTransportException.normalize(normalized)).isSameAs(normalized);
    }
  }

  @Test
  void ordinaryProviderTextCannotMasqueradeAsAnAccountDiagnostic() {
    var normalized =
        ResponsesTransportException.normalize(
            new IllegalStateException("chatgpt_auth_invalid PRIVATE token"));
    assertThat(normalized.diagnosticCode()).isEqualTo("provider_failure");
    assertThat(normalized.diagnosticMessage()).doesNotContain("PRIVATE", "token");
    assertThat(normalized.getCause()).isNull();
  }
}
