package dev.mikoto2000.rei.activity;

import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

@Component
@Command(name="activity",description="Activity timeline: today, yesterday, summary, YYYY-MM-DD, pause, resume")
public class ActivityCommand implements java.util.concurrent.Callable<Integer> {
  private final ActivityTimeline timeline;
  private final ActivityCapture capture;
  private final ActivityProperties properties;
  @Parameters(arity="0..1",defaultValue="today") private String action;
  @Spec private picocli.CommandLine.Model.CommandSpec spec;
  public ActivityCommand() {this(null,null,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public ActivityCommand(ActivityTimeline timeline,ActivityCapture capture,ActivityProperties properties) {this.timeline=timeline;this.capture=capture;this.properties=properties;}
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
