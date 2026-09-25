package dev.mikoto2000.rei.activity;

import java.util.*;

/** Display saved diagnostics only. No image, prompt, response, or window-title rendering. */
public final class ActivityEvidenceDisplayFormatter {
  public enum Source { WINDOW_METADATA, FOREGROUND_VISION, BACKGROUND_VISION }
  public Set<Source> sources(ActivityRecord r) {
    var result=EnumSet.noneOf(Source.class);var d=r.detection();
    if(d==null)return result;
    if(d.classificationSources().stream().anyMatch(s->Set.of("FOREGROUND_WINDOW","WINDOW_TITLE","VISIBLE_WINDOWS","WINDOW_METADATA").contains(s)))result.add(Source.WINDOW_METADATA);
    var vision=VisionDiagnostics.of(d);
    if(vision.foreground()!=null && vision.foreground().state()==VisionDiagnostics.State.USED)result.add(Source.FOREGROUND_VISION);
    if(vision.background()!=null && vision.background().state()==VisionDiagnostics.State.USED)result.add(Source.BACKGROUND_VISION);
    return result;
  }
  public String evidence(ActivityRecord r) {
    var sources=sources(r);
    return sources.isEmpty()?"Unknown":String.join(" + ",sources.stream().map(s->switch(s) {
      case WINDOW_METADATA -> "Window";case FOREGROUND_VISION -> "Foreground Vision";case BACKGROUND_VISION -> "Background Vision";
    }).toList());
  }
  public String verbose(ActivityRecord r) {
    var out=new StringBuilder("    confidence: "+r.confidence()+"\n");var d=r.detection();
    if(d!=null) {
      out.append("    mode: ").append(clean(d.classificationMode())).append("; status: ").append(clean(d.status())).append('\n');
      out.append("    evidence sources (saved): ").append(clean(d.classificationSources().toString())).append('\n');
      if(d.fieldConfidence()!=null) {var c=d.fieldConfidence();out.append("    confidence: category=").append(c.category()).append(" application=").append(c.application()).append(" service=").append(c.service()).append(" project=").append(c.project()).append(" content=").append(c.content()).append('\n');}
      if(!r.inference().activities().isEmpty()) {
        var a=r.inference().activities().getFirst();out.append("    category: ").append(clean(a.type())).append("; service: ").append(clean(a.service())).append("; application: ").append(clean(a.application())).append('\n');
      }
      var diagnostics=d.diagnostics();
      if(diagnostics!=null) {
        out.append("    usable: ").append(diagnostics.classificationUsable()).append('\n');
        out.append("    winning rule: ").append(clean(diagnostics.winningRuleId())).append("; matched: ").append(clean(String.valueOf(diagnostics.matchedRuleIds()))).append('\n');
        out.append("    vision required: ").append(diagnostics.visionRequired()).append("; reason: ").append(clean(diagnostics.visionRequiredReason())).append('\n');
        out.append("    unknown reasons: ").append(clean(String.valueOf(diagnostics.unknownReasons()))).append('\n');
      }
      out.append("    classification reason: ").append(clean(d.reason())).append('\n');
    }
    var v=VisionDiagnostics.of(d);vision(out,"Foreground Vision",v.foreground());vision(out,"Background Vision",v.background());
    return out.toString();
  }
  private static void vision(StringBuilder out,String label,VisionDiagnostics.Result result) {
    out.append("    ").append(label).append(": ").append(result==null?VisionDiagnostics.State.UNKNOWN:result.state());
    if(result!=null && result.failure()!=null)out.append("; reason: ").append(result.failure());
    out.append('\n');
  }
  static String clean(String value) {
    if(value==null)return "Unknown";
    var text=value.replaceAll("[\\p{Cntrl}\\p{Cf}\\s]+"," ").strip();return text.substring(0,Math.min(240,text.length()));
  }
}
