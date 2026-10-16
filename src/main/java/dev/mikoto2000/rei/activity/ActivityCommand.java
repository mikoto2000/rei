package dev.mikoto2000.rei.activity;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Component
@Command(
    name = "activity",
    description = "活動の時系列と分類を表示します",
    subcommands = {
      ActivityCommand.SummaryCommand.class,
      ActivityCommand.BehaviorCommand.class,
      ActivityCommand.ClassificationCommand.class,
      ActivityCommand.WeeklyCommand.class,
      ActivityCommand.MonthlyCommand.class,
      ActivityCommand.CoachingCommand.class,
      ActivityCommand.ContextCommand.class
    })
public class ActivityCommand implements java.util.concurrent.Callable<Integer> {
  private final ActivityTimeline timeline;
  private final ActivityCapture capture;
  private final ActivityProperties properties;
  private final dev.mikoto2000.rei.activity.behavior.BehaviorService behavior;
  private ClassificationToolkit toolkit;
  private ClassificationRuleSuggestions suggestions;
  private ActivityTimelinePresentationService presentation;
  private PeriodCoachingService coaching;

  @org.springframework.beans.factory.annotation.Autowired
  void periodCoaching(PeriodCoachingService coaching) {
    this.coaching = coaching;
  }

  private ActivityWorkContextService workLinks;
  private dev.mikoto2000.rei.core.project.ProjectService projects;

  @org.springframework.beans.factory.annotation.Autowired
  void workContextLinks(
      ActivityWorkContextService workLinks,
      dev.mikoto2000.rei.core.project.ProjectService projects) {
    this.workLinks = workLinks;
    this.projects = projects;
  }

  @org.springframework.beans.factory.annotation.Autowired
  void timelinePresentation(ActivityTimelinePresentationService presentation) {
    this.presentation = presentation;
  }

  @Option(names = "--verbose", description = "保存済みの根拠、画像認識、ルール、行動評価の診断情報を表示します")
  private boolean verbose;

  @org.springframework.beans.factory.annotation.Autowired
  void operationalToolkit(
      ClassificationToolkit toolkit, ClassificationRuleSuggestions suggestions) {
    this.toolkit = toolkit;
    this.suggestions = suggestions;
  }

  @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "today",
      paramLabel = "today|yesterday|YYYY-MM-DD|pause|resume",
      completionCandidates = ActionCandidates.class)
  private String action;

  public static final class ActionCandidates implements Iterable<String> {
    @Override
    public java.util.Iterator<String> iterator() {
      return java.util.List.of("today", "yesterday", "pause", "resume").iterator();
    }
  }

  @Spec private picocli.CommandLine.Model.CommandSpec spec;

  public ActivityCommand() {
    this(null, null, null);
  }

  public ActivityCommand(
      ActivityTimeline timeline, ActivityCapture capture, ActivityProperties properties) {
    this(timeline, capture, properties, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ActivityCommand(
      ActivityTimeline timeline,
      ActivityCapture capture,
      ActivityProperties properties,
      dev.mikoto2000.rei.activity.behavior.BehaviorService behavior) {
    this.timeline = timeline;
    this.capture = capture;
    this.properties = properties;
    this.behavior = behavior;
    this.presentation = new ActivityTimelinePresentationService(timeline, null);
  }

  private ActivityCommand reportingCopy() {
    var copy = new ActivityCommand(timeline, capture, properties, behavior);
    copy.toolkit = toolkit;
    copy.suggestions = suggestions;
    copy.presentation = presentation;
    copy.coaching = coaching;
    copy.workLinks = workLinks;
    copy.projects = projects;
    return copy;
  }

  private static String report(java.util.concurrent.Callable<Integer> command) {
    var text = new java.io.StringWriter();
    var out = new java.io.PrintWriter(text, true);
    new picocli.CommandLine(command).setOut(out).setErr(out);
    try {
      command.call();
    } catch (RuntimeException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalStateException("Activity report failed", error);
    }
    out.flush();
    return text.toString().replaceFirst("[\\r\\n]+$", "");
  }

  public String reportTimeline(String date, boolean details) {
    var command = reportingCopy();
    command.action = date == null ? "today" : date;
    command.verbose = details;
    return report(command);
  }

  public String reportPause() {
    return reportTimeline("pause", false);
  }

  public String reportResume() {
    return reportTimeline("resume", false);
  }

  public String reportSummary(String date) {
    var command = new SummaryCommand();
    command.parent = this;
    command.date = date == null ? "today" : date;
    return report(command);
  }

  private String reportPeriod(PeriodCommand command, String date) {
    command.parent = this;
    command.date = date == null ? "today" : date;
    return report(command);
  }

  public String reportWeekly(String date) {
    return reportPeriod(new WeeklyCommand(), date);
  }

  public String reportMonthly(String date) {
    return reportPeriod(new MonthlyCommand(), date);
  }

  public String reportContext(String date) {
    var command = new ContextCommand();
    command.parent = this;
    command.date = date == null ? "today" : date;
    return report(command);
  }

  public String reportCoachingStatus() {
    return reportCoaching("status", null, null, null);
  }

  public String reportCoachingEnabled(boolean enabled, long revision) {
    return reportCoaching(enabled ? "on" : "off", null, null, revision);
  }

  public String reportCoachingConfigure(PeriodCoaching.Settings settings, long revision) {
    return reportCoaching("configure", null, settings, revision);
  }

  public String reportCoachingWeekly(String date) {
    return reportCoaching("weekly", date, null, null);
  }

  public String reportCoachingMonthly(String date) {
    return reportCoaching("monthly", date, null, null);
  }

  private String reportCoaching(
      String action, String date, PeriodCoaching.Settings settings, Long revision) {
    var command = new CoachingCommand();
    command.parent = this;
    command.action = action;
    command.date = date;
    command.expectedRevision = revision;
    if (settings != null) {
      command.categories = settings.categories();
      command.targetShare = settings.targetShare();
      command.observedMinutes = settings.minimumObservedMinutes();
      command.coverage = settings.minimumCoverage();
      command.unknownShare = settings.maximumUnknownShare();
      command.cooldownDays = settings.cooldownDays();
    }
    return report(command);
  }

  private String reportBehavior(String action, int top) {
    var command = new BehaviorCommand();
    command.parent = this;
    command.action = action;
    command.top = top;
    return report(command);
  }

  public String reportBehaviorStatus() {
    return reportBehavior("status", 20);
  }

  public String reportBehaviorEnabled(boolean enabled) {
    return reportBehavior(enabled ? "on" : "off", 20);
  }

  public String reportBehaviorEvaluation() {
    return reportBehavior("evaluate", 20);
  }

  public String reportUncertain(int top) {
    return reportBehavior("uncertain", top);
  }

  public String reportBehaviorRuleSuggestion() {
    return reportBehavior("suggest-rules", 20);
  }

  private String reportClassification(String action, int top) {
    var command = new ClassificationCommand();
    command.parent = this;
    command.action = action;
    command.top = top;
    return report(command);
  }

  public String reportClassificationStatus() {
    return reportClassification("status", 20);
  }

  public String reportClassificationReload() {
    return reportClassification("reload", 20);
  }

  public String reportUnknown(int top) {
    return reportClassification("unknowns", top);
  }

  public String reportRules() {
    return reportClassification("rules", 20);
  }

  public String reportClassificationRuleSuggestion() {
    return reportClassification("suggest-rules", 20);
  }

  @Command(name = "summary", description = "指定日の活動サマリーを表示します（省略時は今日）")
  public static class SummaryCommand implements java.util.concurrent.Callable<Integer> {
    @ParentCommand private ActivityCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;

    @Parameters(
        index = "0",
        arity = "0..1",
        defaultValue = "today",
        paramLabel = "today|yesterday|YYYY-MM-DD",
        completionCandidates = DateCandidates.class)
    private String date;

    public static class DateCandidates implements Iterable<String> {
      public java.util.Iterator<String> iterator() {
        return java.util.List.of("today", "yesterday").iterator();
      }
    }

    @Override
    public Integer call() {
      try {
        org.slf4j.LoggerFactory.getLogger(SummaryCommand.class)
            .debug("[summary-trace] command date={} handler=ActivityCommand.SummaryCommand", date);
        spec.commandLine().getOut().println(parent.timeline.trendSummary(date));
        return 0;
      } catch (java.time.DateTimeException e) {
        spec.commandLine().getErr().println(e.getMessage());
        spec.commandLine()
            .getErr()
            .println("Usage: /activity summary [today|yesterday|YYYY-MM-DD]");
        return 2;
      } catch (Exception e) {
        spec.commandLine()
            .getErr()
            .println(
                "Activity の操作に失敗しました。ログを確認してください。"
                    + " 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(e));
        return 1;
      }
    }
  }

  public abstract static class PeriodCommand implements java.util.concurrent.Callable<Integer> {
    @ParentCommand private ActivityCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;

    @Parameters(
        index = "0",
        arity = "0..1",
        defaultValue = "today",
        paramLabel = "today|yesterday|YYYY-MM-DD",
        completionCandidates = SummaryCommand.DateCandidates.class)
    private String date;

    protected abstract ActivityPeriodAnalysis.Period period();

    @Override
    public Integer call() {
      try {
        spec.commandLine().getOut().println(parent.timeline.periodAnalysis(period(), date));
        return 0;
      } catch (java.time.DateTimeException | IllegalArgumentException e) {
        spec.commandLine()
            .getErr()
            .println(
                "日付を today|yesterday|YYYY-MM-DD で指定してください（未来は不可）。"
                    + " 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(e));
        return 2;
      } catch (Exception e) {
        spec.commandLine()
            .getErr()
            .println(
                "Activity 分析に失敗しました。ログを確認してください。"
                    + " 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(e));
        return 1;
      }
    }
  }

  @Command(name = "weekly", description = "暦週単位の活動分析と前週との比較を表示します")
  public static class WeeklyCommand extends PeriodCommand {
    protected ActivityPeriodAnalysis.Period period() {
      return ActivityPeriodAnalysis.Period.WEEK;
    }
  }

  @Command(name = "monthly", description = "暦月単位の活動分析と前月との比較を表示します")
  public static class MonthlyCommand extends PeriodCommand {
    protected ActivityPeriodAnalysis.Period period() {
      return ActivityPeriodAnalysis.Period.MONTH;
    }
  }

  @Command(name = "coaching", description = "期間別コーチングの状態表示・設定・有効化・無効化・週次／月次実行を行います")
  public static class CoachingCommand implements java.util.concurrent.Callable<Integer> {
    private Long expectedRevision;
    @ParentCommand private ActivityCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;

    @Parameters(
        index = "0",
        arity = "0..1",
        defaultValue = "status",
        completionCandidates = CoachingCandidates.class)
    private String action;

    @Parameters(index = "1", arity = "0..1", paramLabel = "YYYY-MM-DD")
    private String date;

    @Option(
        names = "--categories",
        split = ",",
        defaultValue = "development,research,documentation")
    private java.util.Set<String> categories;

    @Option(names = "--target-share", defaultValue = "0.6")
    private double targetShare;

    @Option(names = "--min-observed-minutes", defaultValue = "120")
    private int observedMinutes;

    @Option(names = "--min-coverage", defaultValue = "0.1")
    private double coverage;

    @Option(names = "--max-unknown-share", defaultValue = "0.25")
    private double unknownShare;

    @Option(names = "--cooldown-days", defaultValue = "7")
    private int cooldownDays;

    public static class CoachingCandidates implements Iterable<String> {
      public java.util.Iterator<String> iterator() {
        return java.util.List.of("status", "configure", "on", "off", "weekly", "monthly")
            .iterator();
      }
    }

    @Override
    public Integer call() {
      try {
        boolean period = action.equals("weekly") || action.equals("monthly");
        if (date != null && !period)
          throw new IllegalArgumentException("date is only supported for weekly/monthly");
        if (!action.equals("configure")
            && java.util.List.of(
                    "--categories",
                    "--target-share",
                    "--min-observed-minutes",
                    "--min-coverage",
                    "--max-unknown-share",
                    "--cooldown-days")
                .stream()
                .anyMatch(
                    name ->
                        spec.commandLine().getParseResult() != null
                            && spec.commandLine().getParseResult().hasMatchedOption(name)))
          throw new IllegalArgumentException("criteria options require configure");
        var service = parent.coaching;
        String result;
        switch (action) {
          case "status" ->
              result = service.status() + "; automaticNotifications=" + automaticEnabled();
          case "on" ->
              result =
                  (automaticEnabled() ? "Coachingを有効にしました（自動通知設定あり）。" : "手動Coachingを有効にしました（通知なし）。")
                      + (expectedRevision == null
                          ? service.setEnabled(true)
                          : service.setEnabledExpected(true, expectedRevision));
          case "off" ->
              result =
                  "Coachingを無効にしました。"
                      + (expectedRevision == null
                          ? service.setEnabled(false)
                          : service.setEnabledExpected(false, expectedRevision));
          case "configure" -> {
            var settings =
                new PeriodCoaching.Settings(
                    false,
                    categories,
                    targetShare,
                    observedMinutes,
                    coverage,
                    unknownShare,
                    cooldownDays);
            result =
                "基準を保存しました。適用するには coaching on を実行してください。"
                    + (expectedRevision == null
                        ? service.configure(settings)
                        : service.configureExpected(settings, expectedRevision));
          }
          case "weekly", "monthly" ->
              result =
                  service.evaluate(
                      action.equals("weekly")
                          ? ActivityPeriodAnalysis.Period.WEEK
                          : ActivityPeriodAnalysis.Period.MONTH,
                      date);
          default ->
              throw new IllegalArgumentException(
                  "coaching: status | configure | on | off | weekly | monthly");
        }
        spec.commandLine()
            .getOut()
            .println(dev.mikoto2000.rei.event.CredentialRedactor.redact(result));
        return 0;
      } catch (java.time.DateTimeException | IllegalArgumentException e) {
        spec.commandLine()
            .getErr()
            .println(
                "Coachingの引数・基準を確認してください。未来日付は指定できません。"
                    + " 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(e));
        return 2;
      } catch (Exception e) {
        if (expectedRevision != null
            && e instanceof java.util.ConcurrentModificationException conflict) throw conflict;
        spec.commandLine()
            .getErr()
            .println(
                "Coachingの操作に失敗しました。ログを確認してください。"
                    + " 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(e));
        return 1;
      }
    }

    private boolean automaticEnabled() {
      return parent.properties != null && parent.properties.getCoaching().isAutomaticEnabled();
    }
  }

  @Command(name = "context", description = "選択中のプロジェクトに保存された活動・作業コンテキストの参照情報を表示します")
  public static class ContextCommand implements java.util.concurrent.Callable<Integer> {
    @ParentCommand private ActivityCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;

    @Parameters(
        index = "0",
        arity = "0..1",
        defaultValue = "today",
        completionCandidates = SummaryCommand.DateCandidates.class)
    private String date;

    @Override
    public Integer call() {
      try {
        var project = parent.projects.currentContext();
        if (project == null) throw new IllegalArgumentException("Select a Project");
        var links = parent.workLinks.links(project.id(), date);
        spec.commandLine()
            .getOut()
            .println(
                dev.mikoto2000.rei.event.CredentialRedactor.redact(parent.workLinks.format(links)));
        return 0;
      } catch (java.time.DateTimeException | IllegalArgumentException e) {
        spec.commandLine()
            .getErr()
            .println(
                "Projectを選択し、日付を today|yesterday|YYYY-MM-DD で指定してください（未来は不可）。"
                    + " 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(e));
        return 2;
      } catch (Exception e) {
        spec.commandLine()
            .getErr()
            .println(
                "Activityの保存参照を取得できませんでした。ログを確認してください。"
                    + " 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(e));
        return 1;
      }
    }
  }

  @Command(name = "behavior", description = "行動評価の有効化・無効化・状態表示・手動評価を行います（手動評価では通知しません）")
  public static class BehaviorCommand implements java.util.concurrent.Callable<Integer> {
    @ParentCommand private ActivityCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;

    @Parameters(
        index = "0",
        arity = "0..1",
        defaultValue = "status",
        completionCandidates = BehaviorCandidates.class)
    private String action;

    @Option(names = "--top", defaultValue = "20")
    private int top;

    public static class BehaviorCandidates implements Iterable<String> {
      @Override
      public java.util.Iterator<String> iterator() {
        return java.util.List.of("on", "off", "status", "evaluate", "uncertain", "suggest-rules")
            .iterator();
      }
    }

    @Override
    public Integer call() {
      try {
        var service = parent.behavior;
        switch (action) {
          case "uncertain" ->
              spec.commandLine().getOut().println(parent.toolkit.listCandidates("uncertain", top));
          case "suggest-rules" ->
              spec.commandLine().getOut().println(parent.suggestions.suggest(true));
          case "on" -> {
            service.setEnabled(true);
            spec.commandLine()
                .getOut()
                .println("Behavior を有効にしました（この起動中のみ）。Activity Capture が有効・稼働中の場合に評価します。");
          }
          case "off" -> {
            service.setEnabled(false);
            spec.commandLine().getOut().println("Behavior を無効にしました。");
          }
          case "status" -> spec.commandLine().getOut().println(service.status());
          case "evaluate" -> {
            var a = service.evaluate();
            spec.commandLine()
                .getOut()
                .println(
                    "通知しない手動評価: severity="
                        + a.severity()
                        + "; reason="
                        + a.reason()
                        + "; continuousObservedMinutes="
                        + a.continuousEntertainmentSeconds() / 60.0);
            for (var w : a.windows())
              spec.commandLine()
                  .getOut()
                  .printf(
                      java.util.Locale.ROOT,
                      "%d分窓: 観測成功=%.1f分 / 評価対象観測=%.1f分 / 娯楽観測=%.1f分 (評価対象の%.1f%%)%n",
                      w.durationMinutes(),
                      w.observedSeconds() / 60.0,
                      w.eligibleObservedSeconds() / 60.0,
                      w.entertainmentObservedSeconds() / 60.0,
                      w.entertainmentRatio() * 100);
          }
          default -> {
            spec.commandLine()
                .getErr()
                .println(
                    "activity behavior: on | off | status | evaluate | uncertain | suggest-rules");
            return 2;
          }
        }
        return 0;
      } catch (Exception e) {
        spec.commandLine()
            .getErr()
            .println(
                "Behavior の操作に失敗しました。"
                    + " 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(e));
        return 1;
      }
    }
  }

  @Command(name = "classification", description = "活動分類の状態表示・再読み込み・未分類項目表示・ルール表示・ルール提案を行います")
  public static class ClassificationCommand implements java.util.concurrent.Callable<Integer> {
    @ParentCommand private ActivityCommand parent;
    @Spec private picocli.CommandLine.Model.CommandSpec spec;

    @Parameters(
        index = "0",
        arity = "0..1",
        defaultValue = "status",
        completionCandidates = ClassificationCandidates.class)
    private String action;

    @Option(names = "--top", defaultValue = "20")
    private int top;

    public static class ClassificationCandidates implements Iterable<String> {
      public java.util.Iterator<String> iterator() {
        return java.util.List.of("status", "reload", "unknowns", "rules", "suggest-rules")
            .iterator();
      }
    }

    public Integer call() {
      try {
        String result;
        switch (action) {
          case "status" -> result = parent.toolkit.status();
          case "reload" -> {
            boolean ok = parent.toolkit.rules().reload();
            spec.commandLine()
                .getOut()
                .println(ok ? "ルールを再読込しました。" : "再読込に失敗しました。前回の有効ルールを維持しています。");
            return ok ? 0 : 1;
          }
          case "unknowns" -> result = parent.toolkit.listCandidates("unknown", top);
          case "rules" -> result = parent.toolkit.listRules();
          case "suggest-rules" -> result = parent.suggestions.suggest(false);
          default -> {
            spec.commandLine()
                .getErr()
                .println(
                    "activity classification: status | reload | unknowns | rules | suggest-rules");
            return 2;
          }
        }
        spec.commandLine().getOut().println(result.isBlank() ? "候補なし。" : result);
        return 0;
      } catch (Exception e) {
        spec.commandLine()
            .getErr()
            .println(
                "分類運用情報を取得できませんでした。有効ルールは維持されています。"
                    + " 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(e));
        return 1;
      }
    }
  }

  @Override
  public Integer call() {
    try {
      String result;
      switch (action) {
        case "pause" -> {
          capture.pause();
          result = "Activity Capture を一時停止しました。";
        }
        case "resume" -> {
          capture.resume();
          result =
              properties.isEnabled()
                  ? "Activity Capture を再開しました。"
                  : "Activity Capture は設定で無効です。rei.activity.enabled=true が必要です。";
        }
        default -> result = presentation.format(action, verbose);
      }
      spec.commandLine().getOut().println(result);
      return 0;
    } catch (java.time.DateTimeException | IllegalArgumentException e) {
      spec.commandLine()
          .getErr()
          .println(
              "activity: [today|yesterday|YYYY-MM-DD] [--verbose] | summary [today|yesterday|YYYY-MM-DD] | pause | resume");
      return 2;
    } catch (Exception e) {
      spec.commandLine()
          .getErr()
          .println(
              "Activity の操作に失敗しました。ログを確認してください。"
                  + " 原因: "
                  + dev.mikoto2000.rei.event.FailureMessages.describe(e));
      return 1;
    }
  }
}
