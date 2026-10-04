package dev.mikoto2000.rei.core.dependency;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DependencyAwaiterTest {
  @Test void timeoutReturnsWaitingWithoutClaimingCompletion() {
    var time=new AtomicLong();var polls=new AtomicLong();
    var awaiter=new DependencyAwaiter(time::get,d->time.addAndGet(d.toNanos()));
    var result=awaiter.await(()->{polls.incrementAndGet();return new DependencyObservation("p",DependencyState.RUNNING,"running");},Duration.ofSeconds(1),()->{});
    assertEquals(DependencyState.WAITING,result.state());assertEquals(1_000_000_000L,time.get());assertTrue(polls.get()<=6);
  }
  @Test void terminalResultEndsWaitingImmediately() {
    var time=new AtomicLong();var awaiter=new DependencyAwaiter(time::get,d->time.addAndGet(d.toNanos()));
    var result=awaiter.await(()->new DependencyObservation("p",time.get()==0?DependencyState.RUNNING:DependencyState.COMPLETED,""),Duration.ofSeconds(10),()->{});
    assertEquals(DependencyState.COMPLETED,result.state());assertEquals(200_000_000L,time.get());
  }
  @Test void cancellationIsCheckedBeforeEveryProbeAndSleep() {
    var time=new AtomicLong();var checks=new AtomicLong();
    var awaiter=new DependencyAwaiter(time::get,d->time.addAndGet(d.toNanos()));
    assertThrows(java.util.concurrent.CancellationException.class,()->awaiter.await(
        ()->new DependencyObservation("p",DependencyState.RUNNING,""),Duration.ofSeconds(10),
        ()->{if(checks.incrementAndGet()>2)throw new java.util.concurrent.CancellationException();}));
    assertTrue(time.get()<=200_000_000L);
    assertThrows(IllegalArgumentException.class,()->awaiter.await(()->null,Duration.ofSeconds(61),()->{}));
  }
  @Test void interruptionDuringProbeCannotClaimCompletion() {
    var awaiter=new DependencyAwaiter(()->0,d->{});
    try {
      assertThrows(java.util.concurrent.CancellationException.class,()->awaiter.await(()->{
        Thread.currentThread().interrupt();return new DependencyObservation("file",DependencyState.COMPLETED,"");
      },Duration.ZERO,()->{}));
    } finally {Thread.interrupted();}
  }
}
