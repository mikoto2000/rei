package dev.mikoto2000.rei.activity;

import java.util.*;

public record ActivityClassification(ActivityRecord.Inference inference,double confidence,List<String> sources,
    Map<String,Double> sourceConfidence,String reason) {
  public ActivityClassification {sources=List.copyOf(sources);sourceConfidence=Map.copyOf(sourceConfidence);}
}
