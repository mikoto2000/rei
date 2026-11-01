package dev.mikoto2000.rei.testsupport;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestWatcher;

/** Opt-in measurements of fixture preparation, test methods and post-test cleanup. */
public final class FixtureTimingExtension
    implements BeforeEachCallback,
        BeforeTestExecutionCallback,
        AfterTestExecutionCallback,
        TestWatcher,
        InvocationInterceptor {
  /** The executing test owns its named inline fixture phases. */
  private static final ThreadLocal<Sample> ACTIVE = new ThreadLocal<>();

  /** Measurements survive the test context store closing before TestWatcher. */
  private final Map<String, Sample> samples = new ConcurrentHashMap<>();

  /** Whether this diagnostic run requested timings. */
  private final boolean enabled;

  /** Monotonic time source. */
  private final LongSupplier clock;

  /** Receives completed measurements. */
  private final Consumer<Measurement> output;

  /** Enables diagnostics only when the measurement property is explicitly supplied. */
  public FixtureTimingExtension() {
    this(
        Boolean.getBoolean("rei.test.fixture-timing"),
        System::nanoTime,
        measurement ->
            System.out.printf(
                "FIXTURE_TIMING|%s|%d|%d|%d|%s%n",
                measurement.test(),
                measurement.setupNanos(),
                measurement.methodNanos(),
                measurement.cleanupNanos(),
                measurement.phases()));
  }

  /** Supplies deterministic measurement boundaries for the extension contract tests. */
  FixtureTimingExtension(boolean enabled, LongSupplier clock, Consumer<Measurement> output) {
    this.enabled = enabled;
    this.clock = clock;
    this.output = output;
  }

  /** Starts before user BeforeEach methods, without changing their execution. */
  @Override
  public void beforeEach(ExtensionContext context) {
    if (!enabled) return;
    var sample = new Sample(clock);
    samples.put(context.getUniqueId(), sample);
    ACTIVE.set(sample);
  }

  /** Separates BeforeEach preparation from the test method. */
  @Override
  public void beforeTestExecution(ExtensionContext context) {
    if (enabled) samples.get(context.getUniqueId()).methodStart = clock.getAsLong();
  }

  /** Starts the cleanup interval after the test method returns. */
  @Override
  public void afterTestExecution(ExtensionContext context) {
    if (!enabled) return;
    samples.get(context.getUniqueId()).methodEnd = clock.getAsLong();
    ACTIVE.remove();
  }

  /** Records successful tests after their context resources have been closed. */
  @Override
  public void testSuccessful(ExtensionContext context) {
    finish(context);
  }

  /** Keeps failed diagnostic runs observable without suppressing their failure. */
  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    finish(context);
  }

  /** Keeps aborted diagnostic runs observable without changing their outcome. */
  @Override
  public void testAborted(ExtensionContext context, Throwable cause) {
    finish(context);
  }

  /** Records suite seed creation separately from per-test fixture preparation. */
  @Override
  public void interceptBeforeAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> method,
      ExtensionContext context)
      throws Throwable {
    if (!enabled) {
      invocation.proceed();
      return;
    }
    long start = clock.getAsLong();
    try {
      invocation.proceed();
    } finally {
      output.accept(
          new Measurement(
              context.getRequiredTestClass().getName()
                  + "#"
                  + method.getExecutable().getName()
                  + ".beforeAll",
              clock.getAsLong() - start,
              0,
              0,
              Map.of()));
    }
  }

  /** Emits the completed lifecycle measurement and releases per-test state. */
  private void finish(ExtensionContext context) {
    if (!enabled) return;
    var sample = samples.remove(context.getUniqueId());
    if (sample == null) return;
    ACTIVE.remove();
    output.accept(
        new Measurement(
            context.getRequiredTestClass().getName() + "#" + context.getUniqueId(),
            sample.methodStart - sample.start,
            sample.methodEnd - sample.methodStart,
            clock.getAsLong() - sample.methodEnd,
            Map.copyOf(sample.phases)));
  }

  /**
   * Times a named inline fixture block without changing its exception or resource handling.
   *
   * @param name fixture operation label
   * @return scope recording elapsed time when closed, or an inert scope when disabled
   */
  public static Phase phase(String name) {
    var sample = ACTIVE.get();
    if (sample == null) return () -> {};
    long start = sample.clock.getAsLong();
    return () -> sample.phases.merge(name, sample.clock.getAsLong() - start, Long::sum);
  }

  /** A timing boundary with no checked close exception. */
  @FunctionalInterface
  public interface Phase extends AutoCloseable {
    /** Records the end of the measured block. */
    @Override
    void close();
  }

  /**
   * Monotonic elapsed times; named phases are subsets of the test-method interval.
   *
   * @param test qualified test identity
   * @param setupNanos user BeforeEach interval
   * @param methodNanos complete test-method interval, including inline fixture blocks
   * @param cleanupNanos post-method interval through context resource closing
   * @param phases named inline fixture durations
   */
  record Measurement(
      String test,
      long setupNanos,
      long methodNanos,
      long cleanupNanos,
      Map<String, Long> phases) {}

  /** Mutable state private to a single test execution. */
  private static final class Sample {
    /** Time source used for named scopes. */
    final LongSupplier clock;

    /** Beginning of user setup. */
    final long start;

    /** Beginning of the test method. */
    long methodStart;

    /** End of the test method. */
    long methodEnd;

    /** Accumulated inline fixture durations. */
    final Map<String, Long> phases = new HashMap<>();

    /** Initializes all boundaries, including for tests failing in setup. */
    Sample(LongSupplier clock) {
      this.clock = clock;
      start = clock.getAsLong();
      methodStart = start;
      methodEnd = start;
    }
  }
}
