package dev.mikoto2000.rei.activity;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import dev.mikoto2000.rei.activity.ActivityRecord.Activity;

/** Foreground corroboration outranks visible-only candidates. Ties across contexts remain unknown. */
public final class ActivityRolePolicy {
  private final double minimumConfidence;
  public ActivityRolePolicy() {this(.5);}
  public ActivityRolePolicy(double minimumConfidence) {
    if(!Double.isFinite(minimumConfidence) || minimumConfidence<0 || minimumConfidence>1) throw new IllegalArgumentException("Invalid primary confidence threshold");
    this.minimumConfidence=minimumConfidence;
  }
  public ActivityRoles classify(ActivityRecord record) {
    var candidates=record.inference().activities().stream().map(ActivityVocabulary::canonical).distinct().toList();
    int best=0;
    var winners=new ArrayList<Activity>();
    for(var candidate:candidates) {
      if(candidate.type().equals("unknown")) continue;
      int score=foregroundScore(candidate,record.foreground());
      if(score>best) {best=score;winners.clear();}
      if(score==best && score>0) winners.add(candidate);
    }
    Activity primary=winners.isEmpty()?null:winners.getFirst();
    if(primary!=null) {
      var first=primary;
      // A browser process alone cannot distinguish two unrelated tabs or services.
      if(winners.stream().anyMatch(a->!sameContextForForeground(first,a))) primary=null;
    }
    double confidence=record.confidence()*(best>=12?.95:.75);
    if(confidence<minimumConfidence) primary=null;
    var secondary=new ArrayList<Activity>();var background=new ArrayList<Activity>();
    for(var a:candidates) {
      if(a.equals(primary)) continue;
      (backgroundCandidate(a)?background:secondary).add(a);
    }
    return new ActivityRoles(primary,secondary,background,primary==null?0:confidence,
        primary==null?List.of("foreground_unresolved_or_low_confidence"):List.of("foreground_process:8","evidence_score:"+best));
  }
  private int foregroundScore(Activity a,ForegroundWindow foreground) {
    if(foreground==null) return 0;
    String title=normalize(foreground.windowTitle());
    if(a.application().isBlank() || !application(a.application()).equals(application(foreground.processName()))) return 0;
    int score=8;
    if(mentions(title,a.contentTitle(),3)) score+=4;
    if(mentions(title,ActivityVocabulary.serviceLabel(a.service()),1) || (a.service().equals("x") && mentions(title,"Twitter",1))) score+=5;
    if(mentions(title,a.projectCandidate(),2)) score+=1;
    // Project names alone are insufficient to identify a specific foreground activity.
    return score;
  }
  private static boolean mentions(String title,String value,int minimum) {
    String token=normalize(value);
    return token.length()>=minimum && Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(token)+"(?![\\p{L}\\p{N}])").matcher(title).find();
  }
  static String normalize(String value) {
    return value==null?"":Normalizer.normalize(value,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
  }
  static String application(String value) {
    String app=normalize(value).replaceFirst("\\.exe$","");
    return switch(app) {
      case "windows terminal","windowsterminal","terminal","wt","local terminal","powershell","pwsh","shell" -> "terminal";
      case "local editor" -> "editor";
      case "visual studio code","code","vscode" -> "vscode";
      case "google chrome","chrome" -> "chrome";
      case "mozilla firefox","firefox" -> "firefox";
      case "microsoft edge","msedge","edge" -> "edge";
      default -> app;
    };
  }
  static String category(Activity a) {
    return ActivityVocabulary.category(a.type());
  }
  static boolean sameContext(Activity a,Activity b) {
    if(a==null || b==null || !category(a).equals(category(b))) return false;
    String p=normalize(a.projectCandidate()),q=normalize(b.projectCandidate());
    if(!p.isEmpty() && !q.isEmpty() && !p.equals(q)) return false;
    if(category(a).equals("development")) return true;
    if(!p.isEmpty() && p.equals(q)) return true;
    return (!normalize(a.service()).isEmpty() && normalize(a.service()).equals(normalize(b.service())))
        || (!application(a.application()).isEmpty() && application(a.application()).equals(application(b.application())));
  }
  static boolean sameRole(Activity a,Activity b) {
    return sameContext(a,b);
  }
  private static boolean sameContextForForeground(Activity a,Activity b) {
    return sameContext(a,b) && normalize(a.service()).equals(normalize(b.service()))
        && normalize(a.contentTitle()).equals(normalize(b.contentTitle()));
  }
  static boolean backgroundCandidate(Activity a) {
    String text=normalize(a.application()+" "+a.service()+" "+a.contentTitle());
    return Set.of("monitoring","dashboard","system_monitor").contains(normalize(a.type()))
        || Pattern.compile("(?i)\\b(btop|htop|grafana|system monitor|overview dashboard|log monitor|tail -f)\\b|ログ監視|ログtail").matcher(text).find();
  }
}
