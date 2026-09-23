package dev.mikoto2000.rei.activity;

import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

@Component
@Command(name="activity",description="Activity timeline: today, yesterday, summary, YYYY-MM-DD, pause, resume, behavior",subcommands=ActivityCommand.BehaviorCommand.class)
public class ActivityCommand implements java.util.concurrent.Callable<Integer> {
  private final ActivityTimeline timeline;
  private final ActivityCapture capture;
  private final ActivityProperties properties;
  private final dev.mikoto2000.rei.activity.behavior.BehaviorService behavior;
  @Parameters(index="0",arity="0..1",defaultValue="today",completionCandidates=ActionCandidates.class) private String action;
  public static final class ActionCandidates implements Iterable<String> {
    @Override public java.util.Iterator<String> iterator() {
      return java.util.List.of("today","yesterday","summary","pause","resume").iterator();
    }
  }
  @Spec private picocli.CommandLine.Model.CommandSpec spec;
  public ActivityCommand() {this(null,null,null);}
  public ActivityCommand(ActivityTimeline timeline,ActivityCapture capture,ActivityProperties properties) {this(timeline,capture,properties,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public ActivityCommand(ActivityTimeline timeline,ActivityCapture capture,ActivityProperties properties,dev.mikoto2000.rei.activity.behavior.BehaviorService behavior) {this.timeline=timeline;this.capture=capture;this.properties=properties;this.behavior=behavior;}
  @Command(name="behavior",description="Behavior evaluation: on, off, status, evaluate (manual, no notification)")
  public static class BehaviorCommand implements java.util.concurrent.Callable<Integer> {
    @ParentCommand private ActivityCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;
    @Parameters(index="0",arity="0..1",defaultValue="status",completionCandidates=BehaviorCandidates.class) private String action;
    public static class BehaviorCandidates implements Iterable<String> {
      @Override public java.util.Iterator<String> iterator() {return java.util.List.of("on","off","status","evaluate").iterator();}
    }
    @Override public Integer call() {
      try {
        var service=parent.behavior;
        switch(action) {
          case "on" -> {service.setEnabled(true);spec.commandLine().getOut().println("Behavior を有効にしました（この起動中のみ）。Activity Capture が有効・稼働中の場合に評価します。");}
          case "off" -> {service.setEnabled(false);spec.commandLine().getOut().println("Behavior を無効にしました。");}
          case "status" -> spec.commandLine().getOut().println(service.status());
          case "evaluate" -> {
            var a=service.evaluate();spec.commandLine().getOut().println("通知しない手動評価: severity="+a.severity()+"; reason="+a.reason()+"; continuousObservedMinutes="+a.continuousEntertainmentSeconds()/60.0);
            for(var w:a.windows()) spec.commandLine().getOut().printf(java.util.Locale.ROOT,"%d分窓: 娯楽観測=%.1f分 / 評価対象観測=%.1f分 (%.1f%%)%n",w.durationMinutes(),w.entertainmentObservedSeconds()/60.0,w.eligibleObservedSeconds()/60.0,w.entertainmentRatio()*100);
          }
          default -> {spec.commandLine().getErr().println("activity behavior: on | off | status | evaluate");return 2;}
        }
        return 0;
      } catch(Exception e) {spec.commandLine().getErr().println("Behavior の操作に失敗しました。");return 1;}
    }
  }
  @Override public Integer call() {
    try {
      String result;
      switch(action) {
        case "summary" -> result=timeline.trendSummary("today");
        case "pause" -> {capture.pause();result="Activity Capture を一時停止しました。";}
        case "resume" -> {capture.resume();result=properties.isEnabled()?"Activity Capture を再開しました。":"Activity Capture は設定で無効です。rei.activity.enabled=true が必要です。";}
        default -> result=timeline.summary(action);
      }
      spec.commandLine().getOut().println(result);return 0;
    }catch(java.time.DateTimeException | IllegalArgumentException e) {spec.commandLine().getErr().println("activity: today | yesterday | summary | YYYY-MM-DD | pause | resume");return 2;}
    catch(Exception e) {spec.commandLine().getErr().println("Activity の操作に失敗しました。ログを確認してください。");return 1;}
  }
}
