package dev.mikoto2000.rei.activity;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputAwareObservationTest {
  private final ActivityChangeScopeTest.Time clock=new ActivityChangeScopeTest.Time();
  private final InputAwareObservation gate=new InputAwareObservation(clock,15,300);
  private DesktopActivityObserver.Lightweight sample(long input,long ticks,String window) {
    return new DesktopActivityObserver.Lightweight(input,ticks,window,"Default",false,true);
  }
  @Test void firstSuccessSkipsUnchangedUntilMaximum() {
    var s=sample(100,1000,"1");assertTrue(gate.begin(s));gate.saved();gate.end(s);
    clock.advance(15);assertFalse(gate.begin(sample(100,16000,"1")));
    clock.advance(285);assertTrue(gate.begin(sample(100,301000,"1")));
  }
  @Test void failuresRetryAndChangesDuringObservationRemainPending() {
    var s=sample(100,1000,"1");assertTrue(gate.begin(s));gate.end(s);assertTrue(gate.begin(s));
    gate.saved();gate.end(sample(200,1100,"2"));assertTrue(gate.begin(sample(200,1200,"2")));
  }
  @Test void windowAndInputChangesAndForceTriggerObservation() {
    var s=sample(100,1000,"1");gate.begin(s);gate.saved();gate.end(s);
    assertTrue(gate.begin(sample(200,1200,"1")));gate.saved();gate.end(sample(200,1200,"1"));
    assertTrue(gate.begin(sample(200,1300,"2")));gate.saved();gate.end(sample(200,1300,"2"));
    gate.force();assertTrue(gate.begin(sample(200,1400,"2")));
  }
  @Test void unsignedWrapAndNonMonotonicInputNeverMissChanges() {
    var s=sample(0xfffffff0L,0xfffffff8L,"1");gate.begin(s);gate.saved();gate.end(s);
    assertTrue(gate.begin(sample(5,0x100000010L,"1")));gate.saved();gate.end(sample(5,0x100000010L,"1"));
    assertTrue(gate.begin(sample(3,0x100000020L,"1")));
  }
  @Test void unknownRestartResumeAndUnlockAreConservative() {
    var s=sample(100,1000,"1");gate.begin(s);gate.saved();gate.end(s);
    assertTrue(gate.begin(null));gate.end(null);
    assertTrue(gate.begin(sample(100,10,"1")));gate.end(s);
    clock.advance(60);assertTrue(gate.begin(sample(100,61000,"1")));gate.end(s);
    assertFalse(gate.begin(new DesktopActivityObserver.Lightweight(100,62000,"1","Winlogon",true,true)));
    assertTrue(gate.begin(sample(100,63000,"1")));
  }
  @Test void concurrentObservationCannotStartAndFailureCannotCommitBaseline() {
    var s=sample(100,1000,"1");assertTrue(gate.begin(s));assertFalse(gate.begin(s));
    gate.end(s);assertTrue(gate.begin(s));
  }
  @Test void transientChangeAndSignalDuringObservationCannotBeAcknowledgedByAnOldSave() {
    var s=sample(100,1000,"1");gate.begin(s);
    assertFalse(gate.begin(sample(200,1200,"2")));gate.saved();gate.end(s);
    assertTrue(gate.begin(s));gate.force();gate.saved();gate.end(s);
    assertTrue(gate.begin(s));
  }
  @Test void shortSleepUsesWorkingTimeRatherThanInferringIdleFromInput() {
    var s=new DesktopActivityObserver.Lightweight(100,1000,"1","Default",false,true,1000);
    gate.begin(s);gate.saved();gate.end(s);clock.advance(15);
    assertTrue(gate.begin(new DesktopActivityObserver.Lightweight(100,16000,"1","Default",false,true,11000)));
  }
}
