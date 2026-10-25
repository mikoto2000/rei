package dev.mikoto2000.rei.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CancellationLifecycleTest {
  @Test
  void nestedBeginMustNotReplaceParentCancellationOrSubscription() {
    var service = new CommandCancellationService();
    var owner = new AgentRunContext("run", "session", Path.of("."), "project");
    var thread = mock(Thread.class);
    var disposed = new AtomicInteger();
    var child = new AtomicInteger();
    try (var scope = AgentRunScope.open(owner)) {
      service.begin(thread);
      service.register(disposed::incrementAndGet);
      service.onCancel("run", child::incrementAndGet);
      assertThat(service.cancelProject("project")).isTrue();
      service.begin(thread);
      assertThat(service.isCancellationRequested()).isTrue();
      assertThat(child).hasValue(1);
      assertThat(disposed).hasValue(1);
      service.clear();
      service.clear();
    }
  }

  @Test
  void failingChildMustNotPreventOtherChildrenStreamOrThreadFromStopping() {
    var service = new CommandCancellationService();
    var thread = mock(Thread.class);
    var other = new AtomicInteger();
    var disposed = new AtomicInteger();
    service.begin(thread);
    service.onCancel(null, () -> { throw new IllegalStateException("secret"); });
    service.onCancel(null, other::incrementAndGet);
    service.register(disposed::incrementAndGet);
    assertThatCode(service::cancel).doesNotThrowAnyException();
    assertThat(other).hasValue(1);
    assertThat(disposed).hasValue(1);
    verify(thread).interrupt();
    assertThat(service.cancel()).isFalse();
    assertThat(other).hasValue(1);
    service.clear();
  }

  @Test
  void nestedCleanupMustNotUnregisterStillRunningParent() {
    var service = new CommandCancellationService();
    var owner = new AgentRunContext("run", "session", Path.of("."), "project");
    var thread = mock(Thread.class);
    try (var scope = AgentRunScope.open(owner)) {
      service.begin(thread);
      service.begin(thread);
      service.clear();
      assertThat(service.cancelProject("project")).isTrue();
      verify(thread).interrupt();
      service.clear();
      assertThat(service.cancelProject("project")).isFalse();
    }
  }
}
