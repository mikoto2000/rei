package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.attention.AttentionRepository;
import dev.mikoto2000.rei.attention.AttentionService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class AutonomousNotificationPipelineTest {
  @TempDir Path root;
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-28T09:00:00Z"), ZoneOffset.UTC);
  private final List<AgentEvent> events = new ArrayList<>();
  private SqliteAutonomousDraftStore drafts;
  private AttentionService attention;
  private AutonomousDraft initial;

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var d = AutonomousActionProperties.Bluesky.defaults();
    drafts =
        new SqliteAutonomousDraftStore(
            new StorageDatabase(root),
            clock,
            new AutonomousActionProperties(
                true,
                new AutonomousActionProperties.Bluesky(
                    true,
                    d.cron(),
                    d.zone(),
                    1,
                    1,
                    d.cooldown(),
                    d.draftExpiration(),
                    d.maxRegenerations(),
                    d.regenerationCooldown(),
                    d.systemPrompt())));
    var claim =
        drafts
            .reserve("initial", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example")
            .claim();
    initial =
        drafts
            .complete(claim, "PUBLIC_BODY", "PRIVATE_REASON", List.of("topic:one:1"))
            .orElseThrow();
    var repository =
        new AttentionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("attention.db")), clock);
    var bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);
    attention = new AttentionService(repository, bus, bus, clock, () -> 0);
    attention.start();
  }

  @AfterEach
  void close() {
    attention.close();
    drafts.stop();
  }

  private AutonomousNotificationDispatcher worker() {
    return new AutonomousNotificationDispatcher(drafts, () -> attention);
  }

  private AutonomousDraftNotifications wrapped(Consumer<AutonomousDraftNotice> delivery) {
    return new AutonomousDraftNotifications() {
      public void deliver(AutonomousDraftNotice notice) {
        delivery.accept(notice);
      }

      public List<Item> listNotices(int limit) {
        return attention.listNotices(limit);
      }

      public Item acknowledgeNotice(String id) {
        return attention.acknowledgeNotice(id);
      }
    };
  }

  @Test
  void aCommittedProposalAppearsInTheInboxAndAcknowledgmentDoesNotApproveOrPublishIt() {
    worker().flush();
    var item = attention.listNotices(20).getFirst();
    assertThat(item.draftId()).isEqualTo(initial.id());
    assertThat(drafts.pendingNotices(20)).isEmpty();
    attention.acknowledgeNotice(item.id());
    assertThat(attention.listNotices(20)).isEmpty();
    assertThat(drafts.get(initial.id())).isEqualTo(initial);
    assertThat(initial.approval()).isNull();
    assertThat(initial.publication()).isNull();
    drafts.recoverNotices();
    worker().flush();
    assertThat(attention.listNotices(20)).isEmpty();
    assertThat(drafts.get(initial.id())).isEqualTo(initial);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().type()).isEqualTo(AgentEventType.ATTENTION_REQUIRED);
    assertThat(events.getFirst().payload().toString())
        .doesNotContain("PUBLIC_BODY", "PRIVATE_REASON", "topic:one:1");
    assertThat(events.getFirst().projectId()).isNull();
    assertThat(events.getFirst().sessionId()).isNull();
    assertThat(events.getFirst().runId()).isNull();
  }

  @Test
  void rejectionResolvesTheReviewAndRestartDoesNotReopenIt() {
    worker().flush();
    drafts.reject(initial.id(), 1, "PRIVATE_FEEDBACK");
    worker().flush();
    assertThat(attention.listNotices(20)).isEmpty();
    drafts.recoverNotices();
    worker().flush();
    assertThat(attention.listNotices(20)).isEmpty();
    assertThat(drafts.get(initial.id()).state()).isEqualTo(AutonomousDraft.State.REJECTED);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().payload().toString()).doesNotContain("PRIVATE_FEEDBACK");
  }

  @Test
  void resolutionDeliveredBeforeAnInFlightOpenCannotBeUndoneAcrossTheTwoDatabases() {
    var raced =
        wrapped(notice -> {
          if (notice.reviewRequired()) {
            drafts.reject(initial.id(), 1, "PRIVATE_FEEDBACK");
            attention.deliver(new AutonomousDraftNotice(initial.id(), false, initial.createdAt()));
          }
          attention.deliver(notice);
        });
    var worker = new AutonomousNotificationDispatcher(drafts, () -> raced);
    worker.flush();
    assertThat(attention.listNotices(20)).isEmpty();
    assertThat(drafts.pendingNotices(20))
        .containsExactly(new AutonomousDraftNotice(initial.id(), false, initial.createdAt()));
    worker.flush();
    assertThat(drafts.pendingNotices(20)).isEmpty();
    assertThat(attention.listNotices(20)).isEmpty();
    assertThat(events).isEmpty();
  }

  @Test
  void aCrashAfterInboxCommitReplaysTheSameIdAndKeepsOneInboxEntry() {
    var fail = new AtomicBoolean(true);
    var crash =
        wrapped(notice -> {
          attention.deliver(notice);
          if (fail.getAndSet(false)) throw new IllegalStateException("PRIVATE_DELIVERY_ERROR");
        });
    new AutonomousNotificationDispatcher(drafts, () -> crash).flush();
    assertThat(attention.listNotices(20)).hasSize(1);
    assertThat(drafts.pendingNotices(20)).hasSize(1);
    worker().flush();
    assertThat(drafts.pendingNotices(20)).isEmpty();
    assertThat(attention.listNotices(20)).hasSize(1);
    assertThat(events.stream().map(AgentEvent::id).distinct().toList()).hasSize(1);
    int sent = events.size();
    worker().flush();
    assertThat(events).hasSize(sent);
    assertThat(drafts.get(initial.id())).isEqualTo(initial);
  }
}
