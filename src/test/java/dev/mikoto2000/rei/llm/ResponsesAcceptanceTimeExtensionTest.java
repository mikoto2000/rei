package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Explicit time-only approval never resets counters, original header or unknown reservations. */
@Tag("integration")
class ResponsesAcceptanceTimeExtensionTest {
  private static final String APPROVAL = "user-20261125-phase9-time-2h";
  @TempDir Path root;
  private Clock clock = Clock.fixed(Instant.parse("2026-11-25T00:27:42Z"), ZoneOffset.UTC);

  private ResponsesAcceptanceBudget budget() {
    return new ResponsesAcceptanceBudget(
        root.resolve("budget.jsonl"), "owned-test",
        new ResponsesAcceptanceBudget.Limits(3, 1000, 600, Duration.ofHours(3), 0, 0, 0), clock);
  }

  @Test
  void onlyExplicitExtensionReopensTimeWithoutChangingTheHeaderOrReservations() throws Exception {
    var slot = budget().reserve(300, 100);
    budget().unknown(slot);
    String original = Files.readString(root.resolve("budget.jsonl"));
    var snapshot = budget().snapshot();
    clock = Clock.offset(clock, Duration.ofHours(3));
    assertThatThrownBy(() -> budget().reserve(1, 1)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> budget().authorizeTimeExtension("unapproved"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(Files.readString(root.resolve("budget.jsonl"))).isEqualTo(original);
    assertThatCode(() -> budget().authorizeTimeExtension(APPROVAL)).doesNotThrowAnyException();
    assertThat(budget().snapshot()).isEqualTo(snapshot);
    assertThat(budget().remainingMillis()).isEqualTo(Duration.ofHours(2).toMillis());
    assertThat(Files.readString(root.resolve("budget.jsonl"))).startsWith(original);
    assertThat(budget().reserve(1, 1).id()).isEqualTo(2);
  }

  @Test
  void repeatedApprovalIsIdempotentAndDoesNotReplenishTheCallQuota() throws Exception {
    budget().reserve(1, 1);
    clock = Clock.offset(clock, Duration.ofHours(3));
    assertThatCode(() -> budget().authorizeTimeExtension(APPROVAL)).doesNotThrowAnyException();
    String approved = Files.readString(root.resolve("budget.jsonl"));
    budget().authorizeTimeExtension(APPROVAL);
    assertThat(Files.readString(root.resolve("budget.jsonl"))).isEqualTo(approved);
    budget().reserve(1, 1);
    budget().reserve(1, 1);
    assertThatThrownBy(() -> budget().reserve(1, 1)).isInstanceOf(IllegalStateException.class);
    clock = Clock.offset(clock, Duration.ofHours(2));
    assertThatThrownBy(() -> budget().remainingMillis()).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> budget().authorizeTimeExtension(APPROVAL))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aFreshJournalOrChangedExtensionCannotCreateANewTimeWindow() throws Exception {
    assertThatThrownBy(() -> budget().authorizeTimeExtension(APPROVAL))
        .isInstanceOf(IllegalStateException.class);
    budget().reserve(1, 1);
    clock = Clock.offset(clock, Duration.ofHours(5));
    assertThatThrownBy(() -> budget().authorizeTimeExtension(APPROVAL))
        .isInstanceOf(IllegalStateException.class);
    Files.writeString(root.resolve("budget.jsonl"),
        "{\"type\":\"E\",\"extension_ms\":14400000,\"approval\":\"" + APPROVAL + "\"}\n",
        java.nio.file.StandardOpenOption.APPEND);
    assertThatThrownBy(() -> budget().snapshot()).isInstanceOf(IllegalStateException.class);
  }
}
