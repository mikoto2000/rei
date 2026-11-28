package dev.mikoto2000.rei.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mikoto2000.rei.autonomous.AutonomousActionProperties;
import dev.mikoto2000.rei.autonomous.AutonomousDraft;
import dev.mikoto2000.rei.autonomous.AutonomousDraftStore;
import dev.mikoto2000.rei.autonomous.AutonomousGenerationService;
import dev.mikoto2000.rei.autonomous.AutonomousPostGenerator;
import dev.mikoto2000.rei.autonomous.AutonomousPublicSources;
import dev.mikoto2000.rei.autonomous.AutonomousTargetResolver;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Tag("integration")
@Timeout(30)
class AutonomousOpportunityWorkerTest {
  private final MutableClock clock = new MutableClock();
  private final AutonomousDraftStore drafts = mock(AutonomousDraftStore.class);
  private final AutonomousGenerationService generation = mock(AutonomousGenerationService.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AutonomousActionProperties.class)
  @ComponentScan(
      basePackageClasses = AutonomousOpportunityScheduler.class,
      resourcePattern = "AutonomousOpportunityScheduler.class")
  static class SchedulerOnly {}

  private ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withUserConfiguration(SchedulerOnly.class)
        .withBean(Clock.class, () -> clock)
        .withBean(AutonomousDraftStore.class, () -> drafts);
  }

  private AutonomousActionProperties settings() {
    var d = AutonomousActionProperties.Bluesky.defaults();
    return new AutonomousActionProperties(
        true,
        new AutonomousActionProperties.Bluesky(
            true,
            "0 * * * * *",
            "",
            d.maxDraftsPerDay(),
            d.maxPendingDrafts(),
            d.cooldown(),
            d.draftExpiration(),
            d.maxRegenerations(),
            d.regenerationCooldown(),
            d.systemPrompt()));
  }

  private AutonomousGenerationService.Result skipped() {
    return new AutonomousGenerationService.Result(
        AutonomousGenerationService.Status.SKIPPED, "fixture", null);
  }

  @Test
  void productionWorkerCallsOnlyScheduledConsiderationOffTheCallerThread() throws Exception {
    var started = new CountDownLatch(1);
    var actualThread = new AtomicReference<Thread>();
    when(generation.consider(anyString(), eq(AutonomousDraft.Trigger.SCHEDULED)))
        .thenAnswer(invocation -> {
          actualThread.set(Thread.currentThread());
          started.countDown();
          return skipped();
        });
    try (var scheduler =
        new AutonomousOpportunityScheduler(settings(), clock, generation, drafts)) {
      clock.at("2026-11-28T12:00:00Z");
      scheduler.tick();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(actualThread.get()).isNotSameAs(Thread.currentThread());
      assertThat(actualThread.get().isDaemon()).isTrue();
      verify(generation)
          .consider("scheduled:bluesky:2026-11-28T12:00:00Z", AutonomousDraft.Trigger.SCHEDULED);
    }
    assertThat(actualThread.get().join(Duration.ofSeconds(5))).isTrue();
    assertThat(actualThread.get().isAlive()).isFalse();
  }

  @Test
  void theWorkerDoesNotInheritPrivateCallerContext() throws Exception {
    var privateContext = new InheritableThreadLocal<String>();
    var captured = new AtomicReference<String>("not-called");
    var started = new CountDownLatch(1);
    privateContext.set("PRIVATE_CALLER_CONTEXT");
    when(generation.consider(anyString(), eq(AutonomousDraft.Trigger.SCHEDULED)))
        .thenAnswer(invocation -> {
          captured.set(privateContext.get());
          started.countDown();
          return skipped();
        });
    try (var scheduler =
        new AutonomousOpportunityScheduler(settings(), clock, generation, drafts)) {
      clock.at("2026-11-28T12:00:00Z");
      scheduler.tick();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(captured.get()).isNull();
    } finally {
      privateContext.remove();
    }
  }

  @Test
  void theExistingClockKeepsOtherObserversResponsiveAndCloseInterruptsTheSingleWorker()
      throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var running = new AtomicBoolean();
    var actualThread = new AtomicReference<Thread>();
    when(generation.consider(anyString(), eq(AutonomousDraft.Trigger.SCHEDULED)))
        .thenAnswer(invocation -> {
          actualThread.set(Thread.currentThread());
          running.set(true);
          started.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            interrupted.countDown();
          } finally {
            running.set(false);
          }
          return skipped();
        });
    var ordinarySchedules = mock(PersistentAgentScheduler.class);
    var others = new AtomicInteger();
    var dispatcher =
        new AgentScheduleDispatcher(
            ordinarySchedules,
            new AgentSchedulerProperties(false),
            new ToolPermissionProperties(false, null, null, null),
            (entry, done) -> {
              throw new AssertionError("Ordinary agent execution is disabled");
            });
    try (var scheduler =
        new AutonomousOpportunityScheduler(settings(), clock, generation, drafts)) {
      dispatcher.setSecretaries(List.of(scheduler, others::incrementAndGet));
      clock.at("2026-11-28T12:00:00Z");
      dispatcher.tick();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(running.get()).isTrue();
      assertThat(others.get()).isEqualTo(1);
      for (int i = 1; i <= 20; i++) {
        clock.at(Instant.parse("2026-11-28T12:00:00Z").plusSeconds(i * 60L).toString());
        dispatcher.tick();
      }
      assertThat(others.get()).isEqualTo(21);
      verify(generation, times(1)).consider(anyString(), eq(AutonomousDraft.Trigger.SCHEDULED));
      verifyNoInteractions(ordinarySchedules);
    } finally {
      release.countDown();
    }
    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(actualThread.get().join(Duration.ofSeconds(5))).isTrue();
    assertThat(actualThread.get().isAlive()).isFalse();
  }

  @Test
  void componentAvailabilityRequiresBothFeatureFlagsAndStartupDoesNotConsiderAnything() {
    for (boolean enabled : new boolean[] {false, true}) {
      for (boolean bluesky : new boolean[] {false, true}) {
        context()
            .withBean(AutonomousGenerationService.class, () -> generation)
            .withPropertyValues(
                "rei.autonomous-action.enabled=" + enabled,
                "rei.autonomous-action.bluesky.enabled=" + bluesky)
            .run(context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(AutonomousOpportunityScheduler.class))
                  .hasSize(enabled && bluesky ? 1 : 0);
              verifyNoInteractions(generation, drafts);
            });
      }
    }
  }

  @Test
  void manualOnlyConfigurationKeepsTheActualGenerationServiceAvailableWithoutPeriodicWork() {
    context()
        .withUserConfiguration(AutonomousGenerationService.class)
        .withBean(AutonomousPublicSources.class, () -> mock(AutonomousPublicSources.class))
        .withBean(
            AutonomousTargetResolver.class,
            () -> () -> new AutonomousDraft.Target("did:plc:example", "rei.example"))
        .withBean(
            AutonomousPostGenerator.class,
            () ->
                sources -> {
                  throw new AssertionError("No startup inference");
                })
        .withPropertyValues(
            "rei.autonomous-action.enabled=true",
            "rei.autonomous-action.bluesky.enabled=true",
            "rei.autonomous-action.bluesky.scheduled-enabled=false")
        .run(context -> {
          assertThat(context)
              .hasNotFailed()
              .hasSingleBean(AutonomousGenerationService.class)
              .hasSingleBean(AutonomousOpportunityScheduler.class);
          clock.at("2026-11-28T12:00:00Z");
          context.getBean(AutonomousOpportunityScheduler.class).tick();
          verifyNoInteractions(drafts);
        });
  }

  @Test
  void springContextCloseCancelsTheAcceptedConsideration() throws Exception {
    var started = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var actualThread = new AtomicReference<Thread>();
    when(generation.consider(anyString(), eq(AutonomousDraft.Trigger.SCHEDULED)))
        .thenAnswer(invocation -> {
          actualThread.set(Thread.currentThread());
          started.countDown();
          try {
            new CountDownLatch(1).await(10, TimeUnit.SECONDS);
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            interrupted.countDown();
          }
          return skipped();
        });
    context()
        .withBean(AutonomousGenerationService.class, () -> generation)
        .withPropertyValues(
            "rei.autonomous-action.enabled=true", "rei.autonomous-action.bluesky.enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(AutonomousOpportunityScheduler.class);
          clock.at("2026-11-28T12:00:00Z");
          context.getBean(AutonomousOpportunityScheduler.class).tick();
          assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        });
    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(actualThread.get().join(Duration.ofSeconds(5))).isTrue();
    assertThat(actualThread.get().isAlive()).isFalse();
  }

  @Test
  void realExecutorShutdownPreservesAnAlreadyInterruptedCaller() {
    var scheduler = new AutonomousOpportunityScheduler(settings(), clock, generation, drafts);
    try {
      Thread.currentThread().interrupt();
      scheduler.close();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aReturnedFailureIsObservableByOpportunityIdWithoutLeakingResultDetails() throws Exception {
    var logger = (Logger) LoggerFactory.getLogger(AutonomousOpportunityScheduler.class);
    var previous = logger.getLevel();
    var output = new ListAppender<ILoggingEvent>();
    output.start();
    logger.setLevel(Level.WARN);
    logger.addAppender(output);
    var started = new CountDownLatch(1);
    when(generation.consider(anyString(), eq(AutonomousDraft.Trigger.SCHEDULED)))
        .thenAnswer(invocation -> {
          started.countDown();
          return new AutonomousGenerationService.Result(
              AutonomousGenerationService.Status.FAILED, "PRIVATE_PROVIDER_TEXT", null);
        });
    clock.at("2026-11-28T12:41:59Z");
    try {
      try (var scheduler =
          new AutonomousOpportunityScheduler(settings(), clock, generation, drafts)) {
        clock.at("2026-11-28T12:42:00Z");
        scheduler.tick();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(output.list)
          .anySatisfy(event ->
              assertThat(event.getFormattedMessage())
                  .contains("scheduled:bluesky:2026-11-28T12:42:00Z"));
      assertThat(output.list)
          .allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE_PROVIDER_TEXT");
            assertThat(event.getThrowableProxy()).isNull();
          });
    } finally {
      logger.detachAppender(output);
      logger.setLevel(previous);
      output.stop();
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> value =
        new AtomicReference<>(Instant.parse("2026-11-28T11:59:59Z"));

    void at(String instant) {
      value.set(Instant.parse(instant));
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    public Instant instant() {
      return value.get();
    }
  }
}
