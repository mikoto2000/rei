package dev.mikoto2000.rei.activity.behavior;

public enum BehaviorSeverity {
  NONE, NOTICE, WARNING, STRONG_WARNING;
  public static BehaviorSeverity max(BehaviorSeverity a,BehaviorSeverity b) {return a.ordinal()>=b.ordinal()?a:b;}
}
