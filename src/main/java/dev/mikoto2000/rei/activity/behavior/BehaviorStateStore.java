package dev.mikoto2000.rei.activity.behavior;

public interface BehaviorStateStore {
  BehaviorState load();
  void save(BehaviorState state,BehaviorAssessment assessment);
  default void appendEvent(BehaviorTimelineEvent event) {}
  default java.util.List<BehaviorTimelineEvent> findEventsBetween(java.time.Instant start,java.time.Instant end) {return java.util.List.of();}
}
