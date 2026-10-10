package dev.mikoto2000.rei.activity;

/** Desktop candidates are supplemental inference; they never enter the primary activity candidate list. */
final class ActivityBackgroundMerge {
  private ActivityBackgroundMerge() {}
  static ActivityRecord merge(ActivityRecord r,ActivityExtractor.Result background,double threshold) {
    if(background.confidence()<threshold || background.inference().activities().isEmpty())return r;
    var d=r.detection();
    if(d==null)d=new ActivityRecord.Detection(null,java.util.List.of(),false,"LEGACY","FINAL",java.util.Map.of(),"");
    var context=new VisionDiagnostics.Context(r.id(),r.capturedAt(),background.inference().activities().stream().limit(3).toList(),Math.min(.7,background.confidence()));
    var vision=VisionDiagnostics.of(d).context(context);
    var detection=new ActivityRecord.Detection(d.evidence(),d.classificationSources(),d.visionUsed(),d.classificationMode(),d.status(),d.sourceConfidence(),d.reason(),d.fieldConfidence(),d.secondaryConfidence(),d.diagnostics(),vision);
    return new ActivityRecord(r.id(),r.capturedAt(),r.durationEstimate(),r.observations(),r.foreground(),r.inference(),r.confidence(),r.screenshotReferences(),r.changeAmount(),r.duplicate(),r.continuityId(),detection);
  }
}
