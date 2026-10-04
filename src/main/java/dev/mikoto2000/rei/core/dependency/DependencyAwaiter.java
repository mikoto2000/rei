package dev.mikoto2000.rei.core.dependency;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import dev.mikoto2000.rei.temporal.MonotonicTimeSource;

/** Bounded polling independent of filesystem/process/API implementations and wall-clock changes. */
public final class DependencyAwaiter {
  @FunctionalInterface public interface Sleeper {void sleep(Duration duration) throws InterruptedException;}
  private final MonotonicTimeSource time;
  private final Sleeper sleeper;
  public DependencyAwaiter(MonotonicTimeSource time,Sleeper sleeper){this.time=time;this.sleeper=sleeper;}
  public DependencyObservation await(Supplier<DependencyObservation> probe,Duration timeout,Runnable check) {
    if(timeout==null||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(60))>0)
      throw new IllegalArgumentException("Dependency timeout must be between 0 and 60 seconds");
    long started=time.nanoTime(),limit=timeout.toNanos();
    while(true) {
      check.run();
      if(Thread.currentThread().isInterrupted())throw new CancellationException("Dependency wait cancelled");
      var observation=probe.get();
      check.run();
      if(observation.state()!=DependencyState.RUNNING && observation.state()!=DependencyState.WAITING)return observation;
      long remaining=limit-(time.nanoTime()-started);
      if(remaining<=0)return new DependencyObservation(observation.id(),DependencyState.WAITING,observation.detail());
      try {sleeper.sleep(Duration.ofNanos(Math.min(remaining,200_000_000L)));}
      catch(InterruptedException error){Thread.currentThread().interrupt();throw new CancellationException("Dependency wait cancelled");}
    }
  }
}
