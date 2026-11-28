package dev.mikoto2000.rei.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.autonomous.AutonomousDraftNotice;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class AutonomousAttentionTest {
  @TempDir Path root;
  private static final Instant AT = Instant.parse("2026-11-28T09:00:00Z");
  private final Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
  private final List<AgentEvent> events = new ArrayList<>();
  private AttentionRepository repository;
  private InMemoryAgentEventBus bus;
  private AttentionService service;
  private final AutonomousDraftNotice open = new AutonomousDraftNotice("draft-one", true, AT);
  private final AutonomousDraftNotice resolved = new AutonomousDraftNotice("draft-one", false, AT);

  private AttentionRepository repository() {
    return new AttentionRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("attention.db")), clock);
  }

  @BeforeEach
  void prepare() {
    repository = repository();
    bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);
    service = new AttentionService(repository, bus, bus, clock, () -> 0);
    service.start();
  }

  @AfterEach
  void close() {
    service.close();
  }

  @Test
  void retriesAndRestartKeepOneGlobalReviewLinkWithoutARunOrProject() {
    service.deliver(open);
    service.deliver(open);
    var items = service.listNotices(20);
    assertThat(items).hasSize(1);
    assertThat(items.getFirst().id()).isEqualTo(open.notificationId());
    assertThat(items.getFirst().draftId()).isEqualTo(open.draftId());
    assertThat(items.getFirst().status()).isEqualTo("OPEN");
    assertThat(items.getFirst().createdAt()).isEqualTo(AT);
    var event =
        events.stream()
            .filter(e -> e.type() == AgentEventType.ATTENTION_REQUIRED)
            .findFirst()
            .orElseThrow();
    assertThat(event.projectId()).isNull();
    assertThat(event.sessionId()).isNull();
    assertThat(event.runId()).isNull();
    assertThat(event.id()).isEqualTo(open.notificationId());
    var restarted = new AttentionService(repository(), bus, bus, clock, () -> 0);
    try {
      restarted.deliver(open);
      assertThat(restarted.listNotices(20)).isEqualTo(items);
    } finally {
      restarted.close();
    }
  }

  @Test
  void acknowledgmentOnlyDismissesTheNoticeAndReplaysDoNotReopenIt() {
    service.deliver(open);
    var before = service.listNotices(20).getFirst();
    var acknowledged = service.acknowledgeNotice(before.id());
    assertThat(acknowledged.id()).isEqualTo(before.id());
    assertThat(acknowledged.draftId()).isEqualTo(before.draftId());
    assertThat(acknowledged.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(service.listNotices(20)).isEmpty();
    service.deliver(open);
    assertThat(service.listNotices(20)).isEmpty();
    assertThat(events).allMatch(e -> e.type() == AgentEventType.ATTENTION_REQUIRED);
  }

  @Test
  void resolutionBeforeCreationLeavesATombstoneThatLateDeliveriesCannotReopen() {
    service.deliver(resolved);
    service.deliver(open);
    service.deliver(open);
    assertThat(service.listNotices(20)).isEmpty();
    assertThat(events).isEmpty();
  }

  @Test
  void resolutionRemovesAnExistingReviewAndSurvivesRestartAndReplay() {
    service.deliver(open);
    service.deliver(resolved);
    assertThat(service.listNotices(20)).isEmpty();
    var restarted = new AttentionService(repository(), bus, bus, clock, () -> 0);
    try {
      restarted.deliver(open);
      assertThat(restarted.listNotices(20)).isEmpty();
    } finally {
      restarted.close();
    }
  }

  @Test
  void otherInboxKindsAndProjectsCannotBeAcknowledgedThroughTheDraftInbox() {
    var ordinary =
        new dev.mikoto2000.rei.secretary.SecretaryNotifications.Notice(
            "ordinary", "real-project", null, "REVIEW", "other message", 3, "INBOX", AT);
    service.deliver(ordinary);
    var other = repository.list("real-project").getFirst();
    assertThat(service.listNotices(20)).isEmpty();
    assertThatThrownBy(() -> service.acknowledgeNotice(other.id()))
        .isInstanceOf(java.util.NoSuchElementException.class);
    assertThat(repository.get("real-project", other.id()).status()).isEqualTo("OPEN");
  }

  @Test
  void notificationDeferralDoesNotPreventResolutionOrReopenItsTombstone() {
    var life = org.mockito.Mockito.mock(dev.mikoto2000.rei.life.LifePolicy.class);
    service.setLifeContext(life);
    assertThatThrownBy(() -> service.deliver(open))
        .isInstanceOf(dev.mikoto2000.rei.life.LifeContext.NotificationDeferred.class);
    assertThat(service.listNotices(20)).isEmpty();
    service.deliver(resolved);
    service.setLifeContext(null);
    service.deliver(open);
    assertThat(service.listNotices(20)).isEmpty();
    assertThat(events).isEmpty();
  }

  @Test
  void aPublicationFailureKeepsTheLocalReceiptForAnIdempotentRetry() {
    var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
    var failingPublisher =
        new dev.mikoto2000.rei.event.AgentEventPublisher() {
          public void publish(AgentEvent event) {
            if (fail.getAndSet(false)) throw new IllegalStateException("PRIVATE_PROVIDER_FRAGMENT");
            bus.publishBoundary(event);
          }
        };
    var retrying = new AttentionService(repository, bus, failingPublisher, clock, () -> 0);
    try {
      assertThatThrownBy(() -> retrying.deliver(open)).isInstanceOf(IllegalStateException.class);
      assertThat(retrying.listNotices(20)).hasSize(1);
      retrying.deliver(open);
      assertThat(retrying.listNotices(20)).hasSize(1);
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().id()).isEqualTo(open.notificationId());
    } finally {
      retrying.close();
    }
  }

  @Test
  void invalidLimitsAndReferencesAreRejected() {
    assertThatThrownBy(() -> service.listNotices(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.listNotices(201)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.acknowledgeNotice("../other"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.acknowledgeNotice(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
