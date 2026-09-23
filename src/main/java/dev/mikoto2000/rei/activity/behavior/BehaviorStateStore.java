package dev.mikoto2000.rei.activity.behavior;

public interface BehaviorStateStore {
  BehaviorState load();
  void save(BehaviorState state,BehaviorAssessment assessment);
}
