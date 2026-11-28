package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AutonomousDraftNoticeTest {
  private static final Instant AT = Instant.parse("2026-11-28T09:00:00Z");

  @Test
  void retriesAndResolutionAddressTheSameInboxItemAcrossRestarts() {
    var open = new AutonomousDraftNotice("draft-one", true, AT);
    var replay = new AutonomousDraftNotice("draft-one", true, AT);
    var resolved = new AutonomousDraftNotice("draft-one", false, AT);
    assertThat(open.notificationId())
        .isEqualTo(open.notificationId())
        .isEqualTo(replay.notificationId())
        .isEqualTo(resolved.notificationId());
    assertThat(UUID.fromString(open.notificationId())).isNotNull();
    assertThat(new AutonomousDraftNotice("draft-two", true, AT).notificationId())
        .isNotEqualTo(open.notificationId());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "../draft", "draft/other", "draft\u001b", "draft?other"})
  void invalidReferencesCannotBecomeNotificationLinks(String id) {
    assertThatThrownBy(() -> new AutonomousDraftNotice(id, true, AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void oversizedIdsAndMissingCreationTimeAreRejected() {
    assertThatThrownBy(() -> new AutonomousDraftNotice("a".repeat(257), true, AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AutonomousDraftNotice("draft-one", true, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
