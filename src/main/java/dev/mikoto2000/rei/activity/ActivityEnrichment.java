package dev.mikoto2000.rei.activity;

import java.util.*;

/** Merge independent fields without losing a known service or mixing conflicting service contexts. */
final class ActivityEnrichment {
  private ActivityEnrichment() {}
  static ActivityRecord merge(ActivityRecord base,ActivityExtractor.Result result) {
    if(base.inference().activities().isEmpty() || result.inference().activities().isEmpty())return base;
    var old=base.inference().activities().getFirst();var next=result.inference().activities().getFirst();
    var a=fields(base);var b=ActivityFieldConfidence.from(next,result.confidence());
    boolean conflict=!ActivityVocabulary.service(old.service()).isEmpty() && !ActivityVocabulary.service(next.service()).isEmpty()
        && !ActivityVocabulary.service(old.service()).equals(ActivityVocabulary.service(next.service()));
    if(conflict && b.service()<=a.service())return base;
    // A changed service cannot inherit project/content/category from its old context.
    if(conflict)a=new ActivityFieldConfidence(0,a.application(),0,0,0);
    boolean category=conflict || b.category()>a.category(),service=b.service()>a.service(),project=b.project()>a.project(),content=b.content()>a.content();
    var primary=new ActivityRecord.Activity(old.monitor(),category?next.type():old.type(),old.application(),
        service?next.service():old.service(),content?next.contentTitle():conflict?"":old.contentTitle(),project?next.projectCandidate():conflict?"":old.projectCandidate());
    var fields=new ActivityFieldConfidence(Math.max(a.category(),b.category()),a.application(),Math.max(a.service(),b.service()),Math.max(a.project(),b.project()),Math.max(a.content(),b.content()));
    var d=base.detection();
    var detection=new ActivityRecord.Detection(d.evidence(),d.classificationSources(),d.visionUsed(),d.classificationMode(),d.status(),d.sourceConfidence(),d.reason(),fields,d.secondaryConfidence(),d.diagnostics(),d.visionDiagnostics());
    var merged=new ActivityRecord(base.id(),base.capturedAt(),base.durationEstimate(),base.observations(),base.foreground(),
        new ActivityRecord.Inference(category?result.inference().summary():base.inference().summary(),List.of(primary)),fields.overall(),base.screenshotReferences(),base.changeAmount(),base.duplicate(),base.continuityId(),detection);
    var secondary=base.inference().activities().stream().skip(1).toList();
    // Preserve existing evidence-classifier secondary candidates when desktop analysis is OFF too.
    var candidates=new ArrayList<>(merged.inference().activities());var roles=new ActivityRolePolicy(0);
    var selected=roles.classify(merged).primary();
    for(var candidate:secondary) {
      if(candidates.contains(candidate))continue;
      candidates.add(candidate);
      var proposed=new ActivityRecord(merged.id(),merged.capturedAt(),merged.durationEstimate(),merged.observations(),merged.foreground(),new ActivityRecord.Inference(merged.inference().summary(),candidates),merged.confidence(),merged.screenshotReferences(),merged.changeAmount(),merged.duplicate(),merged.continuityId(),merged.detection());
      if(!Objects.equals(selected,roles.classify(proposed).primary()))candidates.removeLast();
    }
    return new ActivityRecord(merged.id(),merged.capturedAt(),merged.durationEstimate(),merged.observations(),merged.foreground(),new ActivityRecord.Inference(merged.inference().summary(),candidates),merged.confidence(),merged.screenshotReferences(),merged.changeAmount(),merged.duplicate(),merged.continuityId(),merged.detection());
  }
  static ActivityFieldConfidence fields(ActivityRecord record) {
    if(record.detection()!=null && record.detection().fieldConfidence()!=null)return record.detection().fieldConfidence();
    return record.inference().activities().isEmpty()?new ActivityFieldConfidence(0,0,0,0,0):ActivityFieldConfidence.from(record.inference().activities().getFirst(),record.confidence());
  }
}
