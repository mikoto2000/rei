package dev.mikoto2000.rei.activity;

import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

@Component
@Command(name="activity",description="Activity timeline and classification",subcommands={ActivityCommand.SummaryCommand.class,ActivityCommand.BehaviorCommand.class,ActivityCommand.ClassificationCommand.class})
public class ActivityCommand implements java.util.concurrent.Callable<Integer> {
  private final ActivityTimeline timeline;
  private final ActivityCapture capture;
  private final ActivityProperties properties;
  private final dev.mikoto2000.rei.activity.behavior.BehaviorService behavior;
  private ClassificationToolkit toolkit;
  private ClassificationRuleSuggestions suggestions;
  @org.springframework.beans.factory.annotation.Autowired
  void operationalToolkit(ClassificationToolkit toolkit,ClassificationRuleSuggestions suggestions){this.toolkit=toolkit;this.suggestions=suggestions;}
  @Parameters(index="0",arity="0..1",defaultValue="today",completionCandidates=ActionCandidates.class) private String action;
  public static final class ActionCandidates implements Iterable<String> {
    @Override public java.util.Iterator<String> iterator() {
      return java.util.List.of("today","yesterday","pause","resume").iterator();
    }
  }
  @Spec private picocli.CommandLine.Model.CommandSpec spec;
  public ActivityCommand() {this(null,null,null);}
  public ActivityCommand(ActivityTimeline timeline,ActivityCapture capture,ActivityProperties properties) {this(timeline,capture,properties,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public ActivityCommand(ActivityTimeline timeline,ActivityCapture capture,ActivityProperties properties,dev.mikoto2000.rei.activity.behavior.BehaviorService behavior) {this.timeline=timeline;this.capture=capture;this.properties=properties;this.behavior=behavior;}
  @Command(name="summary",description="Activity Summary for a local date (default: today)")
  public static class SummaryCommand implements java.util.concurrent.Callable<Integer> {
    @ParentCommand private ActivityCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;
    @Parameters(index="0",arity="0..1",defaultValue="today",paramLabel="today|yesterday|YYYY-MM-DD",completionCandidates=DateCandidates.class)
    private String date;
    public static class DateCandidates implements Iterable<String> {
      public java.util.Iterator<String> iterator() {return java.util.List.of("today","yesterday").iterator();}
    }
    @Override public Integer call() {
      try {spec.commandLine().getOut().println(parent.timeline.trendSummary(date));return 0;}
      catch(java.time.DateTimeException e) {
        spec.commandLine().getErr().println(e.getMessage());
        spec.commandLine().getErr().println("Usage: /activity summary [today|yesterday|YYYY-MM-DD]");return 2;
      }
      catch(Exception e) {spec.commandLine().getErr().println("Activity の操作に失敗しました。ログを確認してください。");return 1;}
    }
  }
  @Command(name="behavior",description="Behavior evaluation: on, off, status, evaluate (manual, no notification)")
  public static class BehaviorCommand implements java.util.concurrent.Callable<Integer> {
    @ParentCommand private ActivityCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;
    @Parameters(index="0",arity="0..1",defaultValue="status",completionCandidates=BehaviorCandidates.class) private String action;
    @Option(names="--top",defaultValue="20") private int top;
    public static class BehaviorCandidates implements Iterable<String> {
      @Override public java.util.Iterator<String> iterator() {return java.util.List.of("on","off","status","evaluate","uncertain","suggest-rules").iterator();}
    }
    @Override public Integer call() {
      try {
        var service=parent.behavior;
        switch(action) {
          case "uncertain" -> spec.commandLine().getOut().println(parent.toolkit.listCandidates("uncertain",top));
          case "suggest-rules" -> spec.commandLine().getOut().println(parent.suggestions.suggest(true));
          case "on" -> {service.setEnabled(true);spec.commandLine().getOut().println("Behavior を有効にしました（この起動中のみ）。Activity Capture が有効・稼働中の場合に評価します。");}
          case "off" -> {service.setEnabled(false);spec.commandLine().getOut().println("Behavior を無効にしました。");}
          case "status" -> spec.commandLine().getOut().println(service.status());
          case "evaluate" -> {
            var a=service.evaluate();spec.commandLine().getOut().println("通知しない手動評価: severity="+a.severity()+"; reason="+a.reason()+"; continuousObservedMinutes="+a.continuousEntertainmentSeconds()/60.0);
            for(var w:a.windows()) spec.commandLine().getOut().printf(java.util.Locale.ROOT,"%d分窓: 観測成功=%.1f分 / 評価対象観測=%.1f分 / 娯楽観測=%.1f分 (評価対象の%.1f%%)%n",w.durationMinutes(),w.observedSeconds()/60.0,w.eligibleObservedSeconds()/60.0,w.entertainmentObservedSeconds()/60.0,w.entertainmentRatio()*100);
          }
          default -> {spec.commandLine().getErr().println("activity behavior: on | off | status | evaluate | uncertain | suggest-rules");return 2;}
        }
        return 0;
      } catch(Exception e) {spec.commandLine().getErr().println("Behavior の操作に失敗しました。");return 1;}
    }
  }
  @Command(name="classification",description="Classification operations: status, reload, unknowns, rules, suggest-rules")
  public static class ClassificationCommand implements java.util.concurrent.Callable<Integer> {
    @ParentCommand private ActivityCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;
    @Parameters(index="0",arity="0..1",defaultValue="status",completionCandidates=ClassificationCandidates.class) private String action;
    @Option(names="--top",defaultValue="20") private int top;
    public static class ClassificationCandidates implements Iterable<String> {
      public java.util.Iterator<String> iterator(){return java.util.List.of("status","reload","unknowns","rules","suggest-rules").iterator();}
    }
    public Integer call() {
      try {
        String result;
        switch(action) {
          case "status" -> result=parent.toolkit.status();
          case "reload" -> {boolean ok=parent.toolkit.rules().reload();spec.commandLine().getOut().println(ok?"ルールを再読込しました。":"再読込に失敗しました。前回の有効ルールを維持しています。");return ok?0:1;}
          case "unknowns" -> result=parent.toolkit.listCandidates("unknown",top);
          case "rules" -> result=parent.toolkit.listRules();
          case "suggest-rules" -> result=parent.suggestions.suggest(false);
          default -> {spec.commandLine().getErr().println("activity classification: status | reload | unknowns | rules | suggest-rules");return 2;}
        }
        spec.commandLine().getOut().println(result.isBlank()?"候補なし。":result);return 0;
      }catch(Exception e){spec.commandLine().getErr().println("分類運用情報を取得できませんでした。有効ルールは維持されています。");return 1;}
    }
  }
  @Override public Integer call() {
    try {
      String result;
      switch(action) {
        case "pause" -> {capture.pause();result="Activity Capture を一時停止しました。";}
        case "resume" -> {capture.resume();result=properties.isEnabled()?"Activity Capture を再開しました。":"Activity Capture は設定で無効です。rei.activity.enabled=true が必要です。";}
        default -> result=timeline.summary(action);
      }
      spec.commandLine().getOut().println(result);return 0;
    }catch(java.time.DateTimeException | IllegalArgumentException e) {spec.commandLine().getErr().println("activity: today | yesterday | summary | YYYY-MM-DD | pause | resume");return 2;}
    catch(Exception e) {spec.commandLine().getErr().println("Activity の操作に失敗しました。ログを確認してください。");return 1;}
  }
}
