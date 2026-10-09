package dev.mikoto2000.rei.llm.capture;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
class CaptureStoreTest {
  CaptureStore store=new CaptureStore(Clock.systemUTC());
  @Test void initiallyOffAndReservationIsScopedAndCancellable(){
    assertThat(store.sessions()).isEmpty();
    store.reserve("client-a","conversation-a");
    assertThat(store.accept("client-b","conversation-a","other","run-b")).isFalse();
    assertThat(store.accept("client-a","conversation-b","other","run-c")).isFalse();
    assertThat(store.off("client-a","conversation-a")).isTrue();
    assertThat(store.accept("client-a","conversation-a","submission-a","run-a")).isFalse();
  }
  @Test void submissionConsumesReservationOnceAndOnlyExactRootIsCaptured(){
    store.reserve("client-a","conversation-a");
    assertThat(store.accept("client-a","conversation-a","submission-a","run-a")).isTrue();
    assertThat(store.accept("client-a","conversation-a","submission-b","run-b")).isFalse();
    assertThat(store.logicalCall("run-b","run-a")).isNull();
    assertThat(store.logicalCall("run-a",null)).isNotNull();
    assertThat(store.sessions().getFirst().submissionId()).isEqualTo("submission-a");
  }
  @Test void onlyOneConcurrentSubmissionWins() throws Exception {
    store.reserve("client","conversation");
    try(var executor=Executors.newFixedThreadPool(2)){
      var gate=new CountDownLatch(1);
      var a=executor.submit(()->{gate.await();return store.accept("client","conversation","a","a");});
      var b=executor.submit(()->{gate.await();return store.accept("client","conversation","b","b");});
      gate.countDown();assertThat(a.get() ^ b.get()).isTrue();
    }
  }
  @Test void limitsRejectWholeBodiesAndMarkAttemptOverflow(){
    activate();var call=store.logicalCall("run",null);
    assertThat(store.prepare(call,new byte[CaptureStore.BODY_LIMIT+1],"application/json",null).captureState()).isEqualTo("SKIPPED_TOO_LARGE");
    for(int i=0;i<16;i++)assertThat(store.prepare(call,new byte[CaptureStore.BODY_LIMIT],"application/json",null).captureState()).isEqualTo("COMPLETE");
    assertThat(store.prepare(call,new byte[1],"application/json",null).captureState()).isEqualTo("SKIPPED_TOO_LARGE");
    for(int i=18;i<32;i++)store.prepare(call,null,null,"SKIPPED_UNSUPPORTED");
    assertThat(store.prepare(call,null,null,"SKIPPED_UNSUPPORTED")).isNull();
    assertThat(store.sessions().getFirst().incomplete()).isTrue();
    assertThat(store.sessions().getFirst().bytes()).isEqualTo(CaptureStore.RUN_LIMIT);
  }
  @Test void deleteClearAndStartFailureRemoveActiveOwnership(){
    activate();assertThat(store.delete("run")).isTrue();assertThat(store.logicalCall("run",null)).isNull();
    activate();store.startFailed("run");assertThat(store.logicalCall("run",null)).isNull();
    store.clear();assertThat(store.sessions()).isEmpty();
  }
  @Test void ttlStartsAtRunEndAndMaximumThreeSessions(){
    var clock=new MutableClock();store=new CaptureStore(clock);
    activate();clock.now=clock.now.plus(Duration.ofHours(1));assertThat(store.sessions()).hasSize(1);
    store.finish("run");clock.now=clock.now.plus(Duration.ofMinutes(30));assertThat(store.sessions()).isEmpty();
    for(int i=0;i<4;i++){store.reserve("client","conversation");store.accept("client","conversation","s"+i,"r"+i);store.finish("r"+i);}
    assertThat(store.sessions()).hasSize(3);
  }
  @Test void originalsAreDefensiveAndIdsCannotCrossSessions(){
    activate();var bytes=new byte[]{1,2,3};var attempt=store.prepare(store.logicalCall("run",null),bytes,"application/json",null);
    bytes[0]=8;var first=store.body(attempt.attemptId());first[0]=9;
    assertThat(store.body(attempt.attemptId())).containsExactly(1,2,3);
    assertThatThrownBy(()->store.body("missing")).isInstanceOf(IllegalArgumentException.class);
    store.delete("run");assertThatThrownBy(()->store.body(attempt.attemptId())).isInstanceOf(IllegalArgumentException.class);
  }
  void activate(){store.reserve("client","conversation");store.accept("client","conversation","submission","run");}
  static class MutableClock extends Clock {
    Instant now=Instant.parse("2026-10-09T00:00:00Z");
    public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
  }
}
