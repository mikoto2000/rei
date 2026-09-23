package dev.mikoto2000.rei.activity.behavior;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mikoto2000.rei.activity.behavior.BehaviorEvaluatorTest.*;

class BehaviorNotificationPolicyTest {
  final BehaviorNotificationPolicy policy=new BehaviorNotificationPolicy(new BehaviorProperties());
  @Test void cooldownCoversSameAndChangedReasons() {
    var a=assess(1800,record(0,1800,"social"));
    var decision=policy.decide(a,BehaviorState.empty(),true,false);assertTrue(decision.allowed());
    var state=decision.state().notified(a);
    assertFalse(policy.decide(assess(1860,record(0,1860,"social")),state,true,false).allowed());
  }
  @Test void escalationCanBreakCooldown() {
    var a=assess(1800,record(0,1800,"social"));var state=policy.decide(a,BehaviorState.empty(),true,false).state().notified(a);
    assertTrue(policy.decide(assess(3600,record(0,3600,"media")),state,true,false).allowed());
  }
  @Test void disabledAndBusySuppress() {
    var a=assess(3600,record(0,3600,"social"));
    assertFalse(policy.decide(a,BehaviorState.empty(),false,false).allowed());assertFalse(policy.decide(a,BehaviorState.empty(),true,true).allowed());
  }
  @Test void recoveryStartsNewEpisodeWithoutRecoveryNotification() {
    var a=assess(1800,record(0,1800,"social"));var state=policy.decide(a,BehaviorState.empty(),true,false).state().notified(a);
    var recovery=assess(2100,record(0,1800,"social"),record(1800,300,"development"));
    var reset=policy.decide(recovery,state,true,false);assertFalse(reset.allowed());assertNull(reset.state().lastNotificationAt());
    var next=assess(3900,record(0,1800,"social"),record(1800,300,"development"),record(2100,1800,"media"));
    var decision=policy.decide(next,reset.state(),true,false);assertTrue(decision.allowed());assertNotEquals(state.episodeId(),decision.state().episodeId());
  }
  @Test void longUnknownDoesNotResetCooldown() {
    var a=assess(3600,record(0,3600,"social"));var state=policy.decide(a,BehaviorState.empty(),true,false).state().notified(a);
    var unknown=assess(4200,record(0,3600,"social"),record(3600,600,"unknown"));
    assertEquals(state.lastNotificationAt(),policy.decide(unknown,state,true,false).state().lastNotificationAt());
  }
  @Test void warningCooldownExpiresAndStrongWarningCanEscalate() {
    var a=assess(3600,record(0,3600,"social"));var state=policy.decide(a,BehaviorState.empty(),true,false).state().notified(a);
    assertFalse(policy.decide(assess(6240,record(0,6240,"social")),state,true,false).allowed());
    assertTrue(policy.decide(assess(6300,record(0,6300,"social")),state,true,false).allowed());
    var warning=assess(7140,record(0,7140,"social"));state=state.notified(warning);
    assertTrue(policy.decide(assess(7200,record(0,7200,"social")),state,true,false).allowed());
  }
}
