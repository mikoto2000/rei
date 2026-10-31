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

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private dev.mikoto2000.rei.life.LifeContext life;

  @Option(names = "--settings-json")
  String lifeJson;

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

  @Option(names = "--json", description = "Show structured meeting details")
  boolean json;

  @Option(names = "--select", split = ",")
  List<String> selected = List.of();

  @Option(names = "--review")
  boolean meetingReview;

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

  private ObjectProvider<SecretaryAutonomy> autonomies;

  @org.springframework.beans.factory.annotation.Autowired
  public void setAutonomies(ObjectProvider<SecretaryAutonomy> autonomies) {
    this.autonomies = autonomies;
  }

  private SecretaryAutonomy autonomy() {
    var service = autonomies == null ? null : autonomies.getIfAvailable();
    if (service == null) throw new IllegalStateException("Secretary autonomy unavailable");
    return service;
  }

  private ObjectProvider<SecretaryConnections> connectionServices;

  @org.springframework.beans.factory.annotation.Autowired
  public void setConnections(ObjectProvider<SecretaryConnections> value) {
    connectionServices = value;
  }

  private SecretaryConnections connections() {
    var value = connectionServices == null ? null : connectionServices.getIfAvailable();
    if (value == null) throw new IllegalStateException("Secretary connections unavailable");
    return value;
  }

  @Option(names = "--connection")
  String connectionId;

  @Option(names = "--service")
  SecretaryProvider.Service service;

  @Option(names = "--resource")
  String resource;

  @Option(names = "--name")
  String connectionName;

  @Option(names = "--connection-version", defaultValue = "-1")
  long connectionVersion;

  @Option(names = "--connection-enabled")
  boolean connectionEnabled;

  @Option(names = "--poll-seconds", defaultValue = "300")
  int pollSeconds;

  @Option(names = "--import-candidates")
  boolean importCandidates;

  @Option(names = "--allow-model-content")
  boolean allowModelContent;

  @Option(names = "--operation")
  String externalOperation;

  @Option(names = "--arguments", defaultValue = "{}")
  String externalArguments;

  @Option(names = "--force-auth")
  boolean forceAuth;

  private java.util.Map<String, String> externalArguments() {
    try {
      var node =
          SecretaryJson.JSON
              .reader()
              .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(externalArguments);
      if (!node.isObject() || node.size() > 32)
        throw new IllegalArgumentException("Arguments must be a string map");
      var result = new java.util.HashMap<String, String>();
      for (var entry : node.properties()) {
        if (!entry.getValue().isTextual())
          throw new IllegalArgumentException("Arguments must be strings");
        result.put(entry.getKey(), entry.getValue().asText());
      }
      return java.util.Map.copyOf(result);
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Invalid JSON arguments");
    }
  }

  @Option(names = "--session")
  String session;

  @Option(names = "--autonomy-enabled")
  boolean autonomyEnabled;

  @Option(names = "--max-level", defaultValue = "1")
  int maxLevel;

  @Option(names = "--auto-prepare")
  boolean autoPrepare;

  @Option(names = "--auto-kind", defaultValue = "PREPARE")
  SecretaryAutonomy.Kind autoKind;

  @Option(names = "--kind", defaultValue = "PREPARE")
  SecretaryAutonomy.Kind kind;

  @Option(names = "--max-concurrent", defaultValue = "1")
  int maxConcurrent;

  @Option(names = "--max-seconds", defaultValue = "300")
  int maxSeconds;

  @Option(names = "--max-llm-calls", defaultValue = "8")
  int maxLlmCalls;

  @Option(names = "--max-daily-calls", defaultValue = "32")
  int maxDailyCalls;

  @Option(names = "--max-retries", defaultValue = "1")
  int maxRetries;

  @Option(names = "--goal")
  String goal;

  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Parameters(index = "0", defaultValue = "list", completionCandidates = ActionCandidates.class)
  String action;

  @Parameters(index = "1", arity = "0..1")
  String id;

  @Option(
      names = "--project",
      description = "Project ID; omit for global life settings and briefings")
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

  private void printResult(Object result)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    com.fasterxml.jackson.databind.JsonNode tree = SecretaryJson.JSON.valueToTree(result);
    var meeting = tree.has("report") ? tree.path("report").path("meeting") : tree.path("meeting");
    if (!json && meeting.path("text").isTextual())
      spec.commandLine().getOut().println(meeting.path("text").asText());
    else if (!json && result instanceof SecretaryMeeting.Decision)
      spec.commandLine().getOut().println("方針を保存しました。ミーティングを終了します。");
    else spec.commandLine().getOut().println(SecretaryJson.JSON.writeValueAsString(result));
  }

  @Override
  public Integer call() {
    try {
      if (action.startsWith("life-")) {
        if (life == null) throw new IllegalStateException("Life context unavailable");
        Object result =
            switch (action) {
              case "life-settings" -> life.settings();
              case "life-status" -> life.status();
              case "life-configure" ->
                  life.configure(
                      SecretaryJson.JSON.readValue(
                          lifeJson, dev.mikoto2000.rei.life.LifeContext.Settings.class));
              default -> throw new IllegalArgumentException("Unknown life operation");
            };
        printResult(result);
        return 0;
      }
      if (project == null
          && java.util.Set.of(
                  "meeting-confirm",
                  "briefing",
                  "review",
                  "briefing-generate",
                  "review-generate",
                  "briefing-history",
                  "briefing-show",
                  "briefing-clear",
                  "briefing-settings",
                  "briefing-check",
                  "briefing-notifications")
              .contains(action)) {
        var service = followups();
        Object result =
            switch (action) {
              case "meeting-confirm" ->
                  service
                      .globalBriefings()
                      .confirmMeeting(reportDate, meetingReview, key, selected);
              case "briefing", "review" ->
                  service.globalBriefings().report(reportDate, action.equals("review"));
              case "briefing-generate", "review-generate" ->
                  service
                      .globalBriefings()
                      .generate(reportDate, action.equals("review-generate"), key);
              case "briefing-history" -> service.globalBriefings().history(limit);
              case "briefing-show" -> service.globalBriefings().show(id);
              case "briefing-clear" ->
                  java.util.Map.of("deleted", service.globalBriefings().deleteHistory());
              case "briefing-settings" -> service.globalBriefings().settings();
              case "briefing-check" -> service.checkGlobalNow();
              case "briefing-notifications" ->
                  service.history(SecretaryNotifications.GLOBAL_SCOPE, limit);
              default -> throw new IllegalArgumentException("Unknown global operation");
            };
        printResult(result);
        return 0;
      }
      if (project == null || project.isBlank())
        throw new IllegalArgumentException("Project required for this operation");
      var ledger = ledgers == null ? null : ledgers.getIfAvailable();
      if (ledger == null) throw new IllegalStateException("Enable rei.secretary.enabled first");
      Object result =
          switch (action) {
            case "connection-list" -> connections().list(project);
            case "connection-configure" ->
                connections()
                    .configure(
                        new SecretaryConnections.Config(
                            project,
                            session,
                            connectionName,
                            service,
                            resource,
                            connectionEnabled,
                            pollSeconds,
                            importCandidates,
                            allowModelContent,
                            zone.isBlank() ? "Asia/Tokyo" : zone),
                        connectionVersion);
            case "connection-show" -> connections().status(project, id);
            case "connection-auth" -> connections().authStatus(project, id);
            case "connection-authorize" -> connections().authorize(project, id, forceAuth);
            case "connection-disconnect" ->
                connections().disconnect(project, id, connectionVersion, false);
            case "connection-poll" -> connections().pollNow(project, id);
            case "connection-items" -> connections().items(project, id, limit);
            case "connection-history" -> connections().history(project, id, limit);
            case "connection-read" ->
                connections().read(project, id, externalOperation, externalArguments());
            case "connection-task" -> connections().toTask(project, id, key);
            case "external-prepare" ->
                connections()
                    .prepare(project, connectionId, key, externalOperation, externalArguments());
            case "external-list" -> connections().actions(project, limit);
            case "external-show" -> connections().action(project, id);
            case "external-history" -> connections().actionHistory(project, id, limit);
            case "external-execute" -> {
              requireExecutionRevision();
              yield connections().execute(project, id, revision);
            }
            case "external-verify" -> {
              requireExecutionRevision();
              yield connections().verify(project, id, revision);
            }
            case "external-cancel" -> {
              requireExecutionRevision();
              yield connections().cancel(project, id, revision);
            }

            case "autonomy-settings" -> autonomy().settings(project);
            case "autonomy-status" -> autonomy().status(project);
            case "autonomy-check" -> {
              autonomy().check();
              yield autonomy().status(project);
            }
            case "autonomy-configure" ->
                autonomy()
                    .configure(
                        new SecretaryAutonomy.Settings(
                            project,
                            autonomyEnabled,
                            session,
                            maxLevel,
                            autoPrepare,
                            autoKind,
                            maxConcurrent,
                            maxSeconds,
                            maxLlmCalls,
                            maxDailyCalls,
                            maxRetries));
            case "work-list" -> autonomy().list(project, limit);
            case "work-show" -> autonomy().get(project, id);
            case "work-history" -> autonomy().history(project, id, limit);
            case "work-add" -> {
              requireExecutionRevision();
              yield autonomy()
                  .create(
                      new SecretaryAutonomy.Request(
                          project,
                          id,
                          revision,
                          key,
                          kind,
                          kind == SecretaryAutonomy.Kind.UPDATE_TASK ? status : null,
                          goal));
            }
            case "work-start", "work-cancel", "work-suspend", "work-resume", "work-verify" -> {
              requireExecutionRevision();
              yield switch (action) {
                case "work-start" -> autonomy().start(project, id, revision);
                case "work-cancel" -> autonomy().cancel(project, id, revision);
                case "work-suspend" -> autonomy().suspend(project, id, revision);
                case "work-resume" -> autonomy().resume(project, id, revision);
                default -> autonomy().verify(project, id, revision);
              };
            }
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
            case "preparation" -> orchestrator().preparation(project);
            case "work-mode" -> java.util.Map.of("mode", autonomy().executionMode(project, id));
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
            case "meeting-confirm" ->
                followups()
                    .confirmMeeting(
                        project,
                        reportDate,
                        meetingReview,
                        key,
                        selected.stream()
                            .map(
                                v ->
                                    v.startsWith(project + "::")
                                        ? v.substring(project.length() + 2)
                                        : v)
                            .toList());
            case "briefing" -> followups().report(project, reportDate, false);
            case "review" -> followups().report(project, reportDate, true);
            case "briefing-generate", "review-generate" ->
                followups().generate(project, reportDate, action.equals("review-generate"), key);
            case "briefing-history" -> followups().briefingHistory(project, limit);
            case "briefing-show" -> followups().briefing(project, id);
            case "briefing-clear" ->
                java.util.Map.of("deleted", followups().deleteBriefings(project));
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
      printResult(result);
      return 0;
    } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException error) {
      spec.commandLine().getErr().println("[error] " + error.getClass().getSimpleName());
      return 2;
    }
  }

  /** 秘書の最初の位置引数で利用できる操作名を提供する。 */
  public static final class ActionCandidates implements Iterable<String> {
    /** 台帳やサービスを参照せず、固定の操作名を返す。 */
    @Override
    public java.util.Iterator<String> iterator() {
      return List.of(
              "list",
              "show",
              "sources",
              "history",
              "related",
              "extract",
              "add",
              "update",
              "approve",
              "done",
              "cancel",
              "settings",
              "configure",
              "briefing",
              "review",
              "briefing-generate",
              "review-generate",
              "briefing-history",
              "briefing-show",
              "briefing-clear",
              "briefing-settings",
              "briefing-check",
              "briefing-notifications",
              "notifications",
              "check",
              "followup-status",
              "meeting-confirm",
              "life-settings",
              "life-status",
              "life-configure",
              "connection-list",
              "connection-configure",
              "connection-show",
              "connection-auth",
              "connection-authorize",
              "connection-disconnect",
              "connection-poll",
              "connection-items",
              "connection-history",
              "connection-read",
              "connection-task",
              "external-prepare",
              "external-list",
              "external-show",
              "external-history",
              "external-execute",
              "external-verify",
              "external-cancel",
              "autonomy-settings",
              "autonomy-status",
              "autonomy-check",
              "autonomy-configure",
              "work-list",
              "work-show",
              "work-history",
              "work-add",
              "work-start",
              "work-cancel",
              "work-suspend",
              "work-resume",
              "work-verify",
              "work-mode",
              "orchestrator-settings",
              "orchestrator-configure",
              "orchestrator-status",
              "suggest",
              "release",
              "suggestions",
              "preparation",
              "suggestion",
              "suggestion-history",
              "feedback")
          .iterator();
    }
  }

  private void requireExecutionRevision() {
    if (!spec.commandLine().getParseResult().hasMatchedOption("--revision"))
      throw new IllegalArgumentException("Revision required");
  }
}
