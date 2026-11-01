package dev.mikoto2000.rei.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

/** The opt-in probe preserves lifecycle boundaries and remains inert by default. */
class FixtureTimingExtensionTest {
  /** Post-method cleanup remains separate from setup and inline fixture preparation. */
  @Test
  void measuresInlineFixtureWithinMethodAndCleanupThroughResultNotification() {
    var clock = new AtomicLong(10);
    var results = new ArrayList<FixtureTimingExtension.Measurement>();
    var timing = new FixtureTimingExtension(true, clock::get, results::add);
    var context = mock(ExtensionContext.class);
    when(context.getUniqueId()).thenReturn("sample[1]");
    doReturn(FixtureTimingExtensionTest.class).when(context).getRequiredTestClass();
    timing.beforeEach(context);
    clock.set(12);
    timing.beforeTestExecution(context);
    clock.set(13);
    try (var phase = FixtureTimingExtension.phase("seed")) {
      clock.set(15);
    }
    clock.set(18);
    timing.afterTestExecution(context);
    clock.set(22);
    timing.testSuccessful(context);
    assertEquals(1, results.size());
    var result = results.getFirst();
    assertEquals(2, result.setupNanos());
    assertEquals(6, result.methodNanos());
    assertEquals(4, result.cleanupNanos());
    assertEquals(Map.of("seed", 2L), result.phases());
    assertEquals(FixtureTimingExtensionTest.class.getName() + "#sample[1]", result.test());
  }

  /** An ordinary regression run does not consult the clock, context or diagnostic sink. */
  @Test
  void disabledProbeDoesNotObserveOrChangeTheTestLifecycle() {
    var context = mock(ExtensionContext.class);
    var clock = mock(java.util.function.LongSupplier.class);
    var output = mock(java.util.function.Consumer.class);
    var timing = new FixtureTimingExtension(false, clock, output);
    timing.beforeEach(context);
    timing.beforeTestExecution(context);
    try (var phase = FixtureTimingExtension.phase("seed")) {}
    timing.afterTestExecution(context);
    timing.testSuccessful(context);
    verifyNoInteractions(context, clock, output);
  }
}

