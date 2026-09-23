package dev.mikoto2000.rei.activity;

import java.util.*;

public record ActivityClassification(ActivityRecord.Inference inference,double confidence,List<String> sources,
    Map<String,Double> sourceConfidence,String reason,ActivityFieldConfidence fieldConfidence,List<Secondary> secondaryConfidence) {
  public record Secondary(ActivityRecord.Activity activity,ActivityFieldConfidence confidence) {}
  public ActivityClassification {sources=List.copyOf(sources);sourceConfidence=Map.copyOf(sourceConfidence);secondaryConfidence=List.copyOf(secondaryConfidence);}
  public ActivityClassification(ActivityRecord.Inference inference,double confidence,List<String> sources,Map<String,Double> sourceConfidence,String reason) {
    this(inference,confidence,sources,sourceConfidence,reason,inference.activities().isEmpty()?new ActivityFieldConfidence(0,0,0,0,0):ActivityFieldConfidence.from(inference.activities().getFirst(),confidence),List.of());
  }
  public boolean usable(double threshold){return fieldConfidence.usable(threshold);}
  public boolean complete(){return fieldConfidence.complete();}
  public boolean partial(){return fieldConfidence.partial();}
}
