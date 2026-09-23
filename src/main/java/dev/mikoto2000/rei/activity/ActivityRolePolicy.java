package dev.mikoto2000.rei.activity;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import dev.mikoto2000.rei.activity.ActivityRecord.Activity;

/** Foreground corroboration outranks visible-only candidates. Ties across contexts remain unknown. */
public final class ActivityRolePolicy {
  public ActivityRoles classify(ActivityRecord record) {
    var candidates=record.inference().activities().stream().distinct().toList();
    int best=0;
    var winners=new ArrayList<Activity>();
    for(var candidate:candidates) {
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
    var secondary=new ArrayList<Activity>();var background=new ArrayList<Activity>();
    for(var a:candidates) {
      if(a.equals(primary)) continue;
      (backgroundCandidate(a)?background:secondary).add(a);
    }
    return new ActivityRoles(primary,secondary,background,primary==null?0:record.confidence()*(best>=4?.9:.7),
        primary==null?List.of("foreground_unresolved"):List.of(best>=4?"foreground_title":"foreground_process"));
  }
  private int foregroundScore(Activity a,ForegroundWindow foreground) {
    if(foreground==null) return 0;
    String title=normalize(foreground.windowTitle());
    int score=application(a.application()).equals(application(foreground.processName())) && !normalize(a.application()).isEmpty()?2:0;
    if(mentions(title,a.contentTitle(),3)) score+=6;
    if(mentions(title,a.service(),1)) score+=4;
    if(mentions(title,a.projectCandidate(),2)) score+=1;
    // Project names alone are insufficient to identify a specific foreground activity.
    return score==1?0:score;
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
      case "windows terminal","windowsterminal","terminal","wt" -> "terminal";
      case "visual studio code","code","vscode" -> "vscode";
      case "google chrome","chrome" -> "chrome";
      case "mozilla firefox","firefox" -> "firefox";
      case "microsoft edge","msedge","edge" -> "edge";
      default -> app;
    };
  }
  static String category(Activity a) {
    return switch(normalize(a.type())) {
      case "coding","development","debugging","programming","devops" -> "coding";
      case "social","sns","media","video","music" -> "social_media";
      case "research","browsing","web" -> "research";
      default -> normalize(a.type());
    };
  }
  static boolean sameContext(Activity a,Activity b) {
    if(a==null || b==null || !category(a).equals(category(b))) return false;
    String p=normalize(a.projectCandidate()),q=normalize(b.projectCandidate());
    if(!p.isEmpty() && !q.isEmpty() && !p.equals(q)) return false;
    if(category(a).equals("coding") || category(a).equals("social_media")) return true;
    if(!p.isEmpty() && p.equals(q)) return true;
    return (!normalize(a.service()).isEmpty() && normalize(a.service()).equals(normalize(b.service())))
        || (!application(a.application()).isEmpty() && application(a.application()).equals(application(b.application())));
  }
  static boolean sameRole(Activity a,Activity b) {
    if(!sameContext(a,b)) return false;
    // Social and media share a merge context, but retain distinct roles in the resulting block.
    return !category(a).equals("social_media") || normalize(a.type()).equals(normalize(b.type()));
  }
  private static boolean sameContextForForeground(Activity a,Activity b) {
    return sameContext(a,b) && normalize(a.service()).equals(normalize(b.service()))
        && normalize(a.contentTitle()).equals(normalize(b.contentTitle()));
  }
  static boolean backgroundCandidate(Activity a) {
    String text=normalize(a.application()+" "+a.service()+" "+a.contentTitle());
    return Set.of("monitoring","dashboard","system_monitor").contains(normalize(a.type()))
        || Pattern.compile("(?i)\\b(btop|htop|grafana|system monitor|overview dashboard|log monitor)\\b|ログ監視").matcher(text).find();
  }
}
