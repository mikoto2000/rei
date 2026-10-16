package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import org.junit.jupiter.api.Test;

class LocalDelegationFailureTest {
  @Test
  void wrappedPermissionAndTransportFailuresAreClassifiedWithoutPrivateMessages() {
    String permission =
        LocalDelegationFailure.describe(
            new RuntimeException(
                new ToolPermissionException("private-tool", PermissionDecision.DENY)),
            false);
    assertTrue(permission.contains("TOOL_PERMISSION"));
    assertFalse(permission.contains("private-tool"));
    assertTrue(
        LocalDelegationFailure.describe(
                new RuntimeException(new java.io.IOException("private-url")), false)
            .contains("MODEL_TRANSPORT"));
    assertFalse(
        LocalDelegationFailure.describe(new IllegalStateException("password=secret"), true)
            .contains("secret"));
  }

  @Test
  void timeoutCancellationAndRateLimitsHaveDistinctCategories() {
    assertTrue(
        LocalDelegationFailure.describe(new java.util.concurrent.TimeoutException(), false)
            .contains("TIMEOUT"));
    assertTrue(
        LocalDelegationFailure.describe(new java.util.concurrent.CancellationException(), false)
            .contains("CANCELLED"));
    var rate =
        org.springframework.web.client.HttpClientErrorException.create(
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
            "private body",
            org.springframework.http.HttpHeaders.EMPTY,
            new byte[0],
            null);
    assertTrue(LocalDelegationFailure.describe(rate, false).contains("MODEL_RATE_LIMIT"));
    assertFalse(LocalDelegationFailure.describe(rate, false).contains("private body"));
  }
}
