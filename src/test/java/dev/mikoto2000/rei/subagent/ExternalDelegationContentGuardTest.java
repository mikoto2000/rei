package dev.mikoto2000.rei.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ExternalDelegationContentGuardTest {
  @ParameterizedTest @ValueSource(strings={
      "{\"password\":{\"value\":\"opaque\"}}",
      "{\"token\":[\"opaque\"]}",
      "{\"result\":{\"client_secret\":{\"example\":\"opaque\"}}}",
      "{\"pass\\u0077ord\":\"opaque\"}",
      "{\"quote\":\"pass\\u0077ord=opaque\"}"})
  void decodedAndStructuredCredentialValuesRemainRestricted(String content) {
    assertThat(ExternalDelegationContentGuard.hasUnmaskedCredentials(content)).isTrue();
  }

  @Test void quotedMaskedReferencesAndOrdinaryStructuredResultsAreAccepted() {
    String content="{\"references\":[{\"quote\":\"spring.datasource.password=\\\"[REDACTED]\\\"\"}],"
        +"\"result\":{\"findings\":[],\"unverified\":[]}}";
    assertThat(ExternalDelegationContentGuard.hasUnmaskedCredentials(content)).isFalse();
  }

  @Test void malformedJsonStillChecksVisibleCredentialsWithoutTryingToRepairTheReply() {
    assertThat(ExternalDelegationContentGuard.hasUnmaskedCredentials("invalid JSON password=opaque")).isTrue();
    assertThat(ExternalDelegationContentGuard.hasUnmaskedCredentials("invalid JSON password=[REDACTED]")).isFalse();
  }
}
