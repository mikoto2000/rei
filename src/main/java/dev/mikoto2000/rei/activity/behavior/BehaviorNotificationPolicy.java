package dev.mikoto2000.rei.activity.behavior;

import java.time.*;

public record BehaviorNotificationPolicy(BehaviorProperties config) {
  public enum Suppression { NONE, DISABLED, AGENT_BUSY, NO_CURRENT_ENTERTAINMENT, EPISODE_NOT_QUALIFIED, BELOW_THRESHOLD, COOLDOWN }
  public record Decision(boolean allowed,Suppression suppression,BehaviorState state,Instant cooldownUntil) {}
  public Decision decide(BehaviorAssessment a,BehaviorState state,boolean enabled,boolean busy) {
    if(!enabled) return new Decision(false,Suppression.DISABLED,state,cooldownUntil(state));
    if(a.recoveredAt()!=null && (state.recoveredThrough()==null || a.recoveredAt().isAfter(state.recoveredThrough())))
      state=new BehaviorState(null,a.recoveredAt(),null,BehaviorSeverity.NONE,BehaviorAssessment.Reason.NONE,a.severity());
    String episode=state.episodeId();
    if(episode==null && a.episodeStartedAt()!=null) episode=a.episodeStartedAt().toString();
    state=new BehaviorState(episode,state.recoveredThrough(),state.lastNotificationAt(),state.lastNotifiedSeverity(),state.lastReason(),a.severity());
    Instant until=cooldownUntil(state);
    Suppression reason=busy?Suppression.AGENT_BUSY:!a.activeEntertainment()?Suppression.NO_CURRENT_ENTERTAINMENT:
        !a.episodeQualified()?Suppression.EPISODE_NOT_QUALIFIED:a.severity()==BehaviorSeverity.NONE?Suppression.BELOW_THRESHOLD:
        until!=null && a.evaluatedAt().isBefore(until) && a.severity().ordinal()<=state.lastNotifiedSeverity().ordinal()?Suppression.COOLDOWN:Suppression.NONE;
    return new Decision(reason==Suppression.NONE,reason,state,until);
  }
  public Instant cooldownUntil(BehaviorState state) {return state.lastNotificationAt()==null?null:state.lastNotificationAt().plusSeconds(config.cooldownSeconds(state.lastNotifiedSeverity()));}
}
