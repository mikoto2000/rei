package dev.mikoto2000.rei.activity;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Foreground rules decide confidence. Visible windows and historical repetition cannot inflate it. */
public final class ActivityClassifier {
  private final WindowActivityRules rules=new WindowActivityRules();
  public ActivityClassification classify(ActivityEvidence evidence) {
    var foreground=evidence.foreground();var match=rules.classify(foreground,"foreground");
    var primary=match.activity();double confidence=match.confidence();
    var fields=match.fields();
    var sources=new ArrayList<>(List.of("FOREGROUND_WINDOW","WINDOW_TITLE"));
    var weights=new LinkedHashMap<String,Double>();weights.put("FOREGROUND_WINDOW",1.0);weights.put("WINDOW_TITLE",confidence);
    String process=ActivityRolePolicy.application(foreground.processName());
    boolean projectMatches=!evidence.projectName().isBlank() && Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])"+Pattern.quote(evidence.projectName())+"(?![\\p{L}\\p{N}_])").matcher(foreground.windowTitle()).find();
    boolean recentTool=evidence.events().stream().anyMatch(e->Objects.equals(e.projectId(),evidence.projectId())
        && !e.at().isAfter(evidence.capturedAt()) && Duration.between(e.at(),evidence.capturedAt()).toSeconds()<=120
        && Set.of("SHELL","FILE_EDIT","BUILD_TEST").contains(e.kind()));
    if(projectMatches && (process.equals("vscode") || process.equals("terminal") && recentTool)) {
      primary=new ActivityRecord.Activity(primary.monitor(),"development",primary.application(),primary.service(),primary.contentTitle(),evidence.projectName());
      confidence=Math.max(confidence,.9);sources.add("PROJECT_CONTEXT");weights.put("PROJECT_CONTEXT",.9);
      fields=new ActivityFieldConfidence(confidence,fields.application(),fields.service(),.9,fields.content());
      if(recentTool) {sources.add("AGENT_EVENT");weights.put("AGENT_EVENT",.9);}
    }
    var history=evidence.history();
    if(history!=null && Objects.equals(history.foreground(),foreground) && !history.at().isAfter(evidence.capturedAt())
        && Duration.between(history.at(),evidence.capturedAt()).toSeconds()<=120) {
      // A prior Vision decision is only context: an unchanged browser title does not prove unchanged content.
      sources.add("ACTIVITY_HISTORY");weights.put("ACTIVITY_HISTORY",Math.min(.4,history.confidence()));
    }
    var candidates=new ArrayList<ActivityRecord.Activity>();candidates.add(primary);
    var secondary=new ArrayList<ActivityClassification.Secondary>();
    for(var visible:evidence.visibleWindows()) {
      if(!visible.visible() || visible.minimized() || visible.offScreen() || visible.window().equals(foreground)) continue;
      var candidate=rules.classify(visible.window(),visible.monitor());
      // A second window of the same application cannot identify the foreground more reliably.
      boolean identifiable=candidate.confidence()>=.8 || candidate.fields().service()>=.8;
      if(identifiable && !ActivityRolePolicy.application(candidate.activity().application()).equals(process)) candidates.add(candidate.activity());
      else if(identifiable && !candidate.activity().service().equals(primary.service()) && !primary.type().equals("unknown")) {
        var probe=new ActivityRecord("probe",evidence.capturedAt(),0,List.of(),foreground,new ActivityRecord.Inference("",candidates),confidence,List.of(),0,false);
        var supplemented=ActivityBackgroundMerge.merge(probe,new ActivityExtractor.Result(new ActivityRecord.Inference("",List.of(candidate.activity())),candidate.confidence()),.5);
        candidates=new ArrayList<>(supplemented.inference().activities());
      }
      if(candidates.contains(candidate.activity()))secondary.add(new ActivityClassification.Secondary(candidate.activity(),candidate.fields().secondary()));
    }
    if(candidates.size()>1) {sources.add("VISIBLE_WINDOWS");weights.put("VISIBLE_WINDOWS",.8);}
    var selected=candidates.stream().distinct().limit(16).toList();
    return new ActivityClassification(new ActivityRecord.Inference("前面ウィンドウの情報に基づく活動候補",selected),fields.overall(),sources,weights,match.rule(),fields,secondary.stream().filter(s->selected.contains(s.activity())).distinct().toList());
  }
}
