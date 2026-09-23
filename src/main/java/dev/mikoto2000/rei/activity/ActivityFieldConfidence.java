package dev.mikoto2000.rei.activity;

/** Independent rule/model confidence, not calibrated probabilities. Missing fields have zero confidence. */
public record ActivityFieldConfidence(double category,double application,double service,double project,double content) {
  public ActivityFieldConfidence {
    for(double value:new double[]{category,application,service,project,content})
      if(!Double.isFinite(value) || value<0 || value>1)throw new IllegalArgumentException("Invalid field confidence");
  }
  public double overall(){return Math.min(category,Math.max(application,service));}
  public boolean usable(double categoryThreshold){return category>0 && category>=categoryThreshold && Math.max(application,service)>=.8;}
  public boolean complete(){return category>0 && application>0 && service>0 && project>0 && content>0;}
  public boolean partial(){return !complete() && Math.max(application,service)>0;}
  public static ActivityFieldConfidence from(ActivityRecord.Activity a,double confidence) {
    return new ActivityFieldConfidence(ActivityVocabulary.category(a.type()).equals("unknown")?0:confidence,
        known(a.application(),confidence),known(a.service(),confidence),known(a.projectCandidate(),confidence),known(a.contentTitle(),confidence));
  }
  public ActivityFieldConfidence secondary(){return new ActivityFieldConfidence(Math.min(category,.75),application,Math.min(service,.8),Math.min(project,.75),Math.min(content,.75));}
  private static double known(String value,double confidence){return value==null || value.isBlank()?0:confidence;}
}
