package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 一般の定期処理が待機しても承認済みの操作を続行できることを検証する。 */
class ApprovalSchedulingTest {
  /** 実際のScheduled注釈を使う最小構成。 */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
          .withUserConfiguration(ProbeConfiguration.class, ApprovalSchedulingConfiguration.class);

  /** 一般のスケジューラーを塞いだ後も承認のpumpが実行される。 */
  @Test
  void approvalPumpContinuesWhileDefaultSchedulerIsBlocked() {
    var release = new CountDownLatch(1);
    runner.run(
        context -> {
          var defaultScheduler = context.getBean("taskScheduler", TaskScheduler.class);
          var started = new CountDownLatch(1);
          defaultScheduler.schedule(
              () -> {
                started.countDown();
                try {
                  release.await();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              },
              Instant.now());
          try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var pumped = new CountDownLatch(1);
            context.getBean(Probe.class).next.set(pumped);
            assertThat(pumped.await(3, TimeUnit.SECONDS))
                .as("approval continuation must not wait for an unrelated scheduled job")
                .isTrue();
          } finally {
            release.countDown();
          }
        });
  }

  /** 一般の自動設定と利用者指定を維持し、専用スケジューラーは閉鎖する。 */
  @Test
  void preservesDefaultConfigurationAndClosesDedicatedScheduler() {
    var dedicated = new AtomicReference<ThreadPoolTaskScheduler>();
    runner
        .withPropertyValues(
            "spring.task.scheduling.pool.size=3",
            "spring.task.scheduling.thread-name-prefix=custom-general-")
        .run(
            context -> {
              var normal = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
              assertThat(normal.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
              assertThat(normal.getThreadNamePrefix()).isEqualTo("custom-general-");
              var approval =
                  context.getBean("approvalContinuationScheduler", ThreadPoolTaskScheduler.class);
              assertThat(approval).isNotSameAs(normal);
              assertThat(approval.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
              dedicated.set(approval);
            });
    assertThat(dedicated.get().getScheduledThreadPoolExecutor().isShutdown()).isTrue();
  }

  /** 実際の承認サービス注釈を使い、ツール実行は行わない。 */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class ProbeConfiguration {
    /** pumpを観測する状態。 */
    @Bean
    Probe probe() {
      return new Probe();
    }

    /** 実際の定期実行エントリを観測する。 */
    @Bean
    ApprovalContinuationService approvals(Probe probe) {
      var service = mock(ApprovalContinuationService.class);
      doAnswer(
              call -> {
                var next = probe.next.get();
                if (next != null) next.countDown();
                return null;
              })
          .when(service)
          .pump();
      return service;
    }
  }

  /** 次回の定期実行を待つための状態。 */
  static class Probe {
    /** 待機開始後の実行だけを数える。 */
    final AtomicReference<CountDownLatch> next = new AtomicReference<>();
  }
}
