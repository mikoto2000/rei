package dev.mikoto2000.rei.secretary;

import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Explicit user operations on the local secretary ledger. */
@Component
@Command(name = "secretary", description = "約束・タスク台帳を操作します", mixinStandardHelpOptions = true)
public class SecretaryCommand implements java.util.concurrent.Callable<Integer> {
  private final ObjectProvider<SecretaryLedger> ledgers;
  private ObjectProvider<SecretaryFollowups> followups;
  private ObjectProvider<SecretaryOrchestrator> orchestrators;

  @org.springframework.beans.factory.annotation.Autowired
  public void setOrchestrators(ObjectProvider<SecretaryOrchestrator> orchestrators) {
    this.orchestrators = orchestrators;
  }

  private SecretaryOrchestrator orchestrator() {
    var service = orchestrators == null ? null : orchestrators.getIfAvailable();
    if (service == null) throw new IllegalStateException("Secretary orchestrator unavailable");
    return service;
  }

  @Option(names = "--proposals-enabled")
  boolean proposalsEnabled;

  @Option(names = "--stopped")
  boolean stopped;

  @Option(names = "--snoozed-until")
  java.time.Instant snoozedUntil;

  @Option(names = "--minimum-seconds", defaultValue = "900")
  long minimumSeconds;

  @Option(names = "--reaction")
  SecretaryOrchestrator.Feedback reaction;

  @Option(names = "--delay-seconds", defaultValue = "0")
  long delaySeconds;

  @org.springframework.beans.factory.annotation.Autowired
  public void setFollowups(ObjectProvider<SecretaryFollowups> followups) {
    this.followups = followups;
  }

  @Option(names = "--date")
  LocalDate reportDate;

  @Option(names = "--zone", defaultValue = "")
  String zone;

  @Option(names = "--briefing-time", defaultValue = "")
  String briefingTime;

  @Option(names = "--review-time", defaultValue = "")
  String reviewTime;

  @Option(names = "--repeat-seconds", defaultValue = "86400")
  long repeatSeconds;

  @Option(names = "--notification-priority", defaultValue = "3")
  int notificationPriority;

  @Option(names = "--destination", defaultValue = "INBOX")
  String destination;

  @Option(names = "--watch-enabled")
  boolean watchEnabled;

  @Option(names = "--notifications")
  boolean notificationsEnabled;

  private SecretaryFollowups followups() {
    var service = followups == null ? null : followups.getIfAvailable();
    if (service == null) throw new IllegalStateException("Secretary followups unavailable");
    return service;
  }

  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Parameters(index = "0", defaultValue = "list")
  String action;

  @Parameters(index = "1", arity = "0..1")
  String id;

  @Option(names = "--project", required = true)
  String project;

  @Option(names = "--title")
  String title;

  @Option(names = "--key")
  String key;

  @Option(names = "--intent", defaultValue = "TODO")
  SecretaryTask.Intent intent;

  @Option(names = "--status", defaultValue = "OPEN")
  SecretaryTask.Status status;

  @Option(names = "--due")
  LocalDate due;

  @Option(names = "--priority", defaultValue = "3")
  int priority;

  @Option(names = "--revision", defaultValue = "0")
  long revision;

  @Option(names = "--tag", split = ",")
  List<String> tags = List.of();

  @Option(names = "--ref", split = ",")
  List<String> references = List.of();

  @Option(names = "--offset", defaultValue = "0")
  int offset;

  @Option(names = "--limit", defaultValue = "100")
  int limit;

  public SecretaryCommand() {
    this(null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public SecretaryCommand(ObjectProvider<SecretaryLedger> ledgers) {
    this.ledgers = ledgers;
  }

  @Override
  public Integer call() {
    try {
      var ledger = ledgers == null ? null : ledgers.getIfAvailable();
      if (ledger == null) throw new IllegalStateException("Enable rei.secretary.enabled first");
      Object result =
          switch (action) {
            case "orchestrator-settings" -> orchestrator().settings(project);
            case "orchestrator-configure" ->
                orchestrator()
                    .configure(
                        new SecretaryOrchestrator.Settings(
                            project,
                            proposalsEnabled,
                            stopped,
                            snoozedUntil,
                            minimumSeconds,
                            repeatSeconds));
            case "orchestrator-status" -> orchestrator().status(project);
            case "suggest" -> orchestrator().evaluate(project);
            case "release" -> orchestrator().release(project);
            case "suggestions" -> orchestrator().suggestions(project, limit);
            case "suggestion" -> orchestrator().suggestion(project, id);
            case "suggestion-history" -> orchestrator().history(project, id, limit);
            case "feedback" -> {
              if (!spec.commandLine().getParseResult().hasMatchedOption("--revision"))
                throw new IllegalArgumentException("Revision required");
              yield orchestrator().feedback(project, id, revision, reaction, delaySeconds);
            }
            case "settings" -> followups().settings(project);
            case "configure" ->
                followups()
                    .configure(
                        new SecretaryFollowups.Settings(
                            project,
                            watchEnabled,
                            notificationsEnabled,
                            briefingTime,
                            reviewTime,
                            zone.isEmpty() ? followups().settings(project).zone() : zone,
                            repeatSeconds,
                            notificationPriority,
                            destination));
            case "briefing" -> followups().report(project, reportDate, false);
            case "review" -> followups().report(project, reportDate, true);
            case "notifications" -> followups().history(project, limit);
            case "check" -> followups().checkNow(project);
            case "followup-status" -> followups().status(project);
            case "list" -> ledger.list(project, offset, limit);
            case "show" -> ledger.get(project, id);
            case "sources" -> ledger.sources(project, id);
            case "history" -> ledger.history(project, id);
            case "related" -> ledger.related(project, id);
            case "extract" -> ledger.extract(project, key, title);
            case "add" ->
                ledger.create(project, key, title, intent, due, priority, tags, references);
            case "update" ->
                ledger.update(
                    project, id, revision, title, intent, status, due, priority, tags, references);
            case "approve" -> ledger.transition(project, id, revision, SecretaryTask.Status.OPEN);
            case "done" -> ledger.transition(project, id, revision, SecretaryTask.Status.DONE);
            case "cancel" ->
                ledger.transition(project, id, revision, SecretaryTask.Status.CANCELLED);
            default ->
                throw new IllegalArgumentException(
                    "Use list|show|sources|history|related|extract|add|update|approve|done|cancel");
          };
      spec.commandLine().getOut().println(SecretaryJson.JSON.writeValueAsString(result));
      return 0;
    } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException error) {
      spec.commandLine().getErr().println("[error] " + error.getClass().getSimpleName());
      return 2;
    }
  }
}
