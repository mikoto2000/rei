package dev.mikoto2000.rei.chatgpt;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class ChatGptRunFailureTest {
  @ParameterizedTest @EnumSource(ChatGptException.Code.class)
  void everyTypedCodeKeepsControlledDiagnosticsWithoutItsPrivateWrapper(ChatGptException.Code code) {
    var failure=ChatGptException.of(code);
    var diagnostic=ChatGptRunFailure.find(new IllegalStateException("PRIVATE wrapper https://secret?token=PRIVATE",failure));
    assertThat(diagnostic.code()).isEqualTo("chatgpt_"+code.name().toLowerCase(Locale.ROOT));
    assertThat(diagnostic.message()).isEqualTo(failure.getMessage()).doesNotContain("PRIVATE","https://secret");
  }

  @ParameterizedTest @EnumSource(ChatGptException.PermissionReason.class)
  void permissionReasonsKeepTheirEnumAndSuggestOnlyTheMatchingRemedy(ChatGptException.PermissionReason reason) {
    var diagnostic=ChatGptRunFailure.diagnostic(ChatGptException.permission(reason));
    assertThat(diagnostic.errorType()).isEqualTo("ChatGptPermissionDenied");
    assertThat(diagnostic.code()).isEqualTo("chatgpt_permission_denied");
    assertThat(diagnostic.message()).contains(reason.name());
    switch(reason) {
      case AUTO_DELEGATION_DISABLED -> assertThat(diagnostic.message()).contains("rei.chatgpt-plan.auto-delegation.enabled");
      case ACCOUNT_NOT_ALLOWED -> assertThat(diagnostic.message()).contains("rei.chatgpt-plan.auto-delegation.allowed-accounts");
      case MODEL_NOT_ALLOWED -> assertThat(diagnostic.message()).contains("rei.chatgpt-plan.auto-delegation.allowed-models","modelId をホストが許可したモデルに修正");
      case ROLE_NOT_ALLOWED -> assertThat(diagnostic.message()).contains("rei.chatgpt-plan.allowed-roles");
      case ACCOUNT_REQUIRED -> assertThat(diagnostic.message()).contains("/chatgpt status","accountRef");
      case MODEL_REQUIRED -> assertThat(diagnostic.message()).contains("/chatgpt models --account","modelId");
      case OWNER_NOT_EXCLUSIVE -> assertThat(diagnostic.message()).contains("親の EXCLUSIVE Run");
    }
  }

  @Test void arbitraryAndCyclicCausesDoNotBecomePublicDiagnostics() {
    assertThat(ChatGptRunFailure.find(new IllegalStateException("PRIVATE"))).isNull();
    var first=new IllegalStateException("PRIVATE first");var second=new IllegalStateException("PRIVATE second",first);first.initCause(second);
    assertThat(ChatGptRunFailure.find(first)).isNull();
  }

  @Test void remoteDiagnosticRetainsOnlyAlreadyControlledMetadata() {
    var failure=ChatGptException.response(ChatGptException.Code.PERMISSION_DENIED,"inference",403,"PRIVATE-MEDIA",100,1024,"PRIVATE-API-CODE");
    assertThat(ChatGptRunFailure.diagnostic(failure).message()).contains("httpStatus=403","contentType=unsupported").doesNotContain("PRIVATE");
  }
}
