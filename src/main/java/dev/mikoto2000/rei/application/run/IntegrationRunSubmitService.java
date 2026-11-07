package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.googlecalendar.command.ScheduleCommand;
import dev.mikoto2000.rei.task.TaskService;
import dev.mikoto2000.rei.task.command.TaskCommand;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * 外部連携実行受付のユースケースを実現する。
 *
 * <p>Explicit integration operations; no command text, reflection target or service selector is
 * accepted.
 */
@Service
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class IntegrationRunSubmitService {
  /** プロジェクト一覧。 */
  private final Function<String, Optional<ProjectContext>> findProject;

  /** 受付から終了までの管理。 */
  private final BiConsumer<String, String> validateSession;

  private final java.util.function.BiFunction<ProjectContext, Admission, AgentRunContext> admitRun;

  private record Admission(
      String session,
      String operation,
      String key,
      String fingerprint,
      java.util.function.Supplier<String> report) {}

  /** 登録台帳。 */
  private final java.util.function.Consumer<AgentRunContext> registerRun;

  private final java.util.function.Consumer<String> forgetRun;

  /** 実行一覧。 */
  private final RunService runs;

  /** 入力の振り分け。 */
  private final BiConsumer<AgentRunContext, Runnable> submitOperation;

  /** キャンセル。 */
  private final java.util.function.Supplier<Runnable> beginCommand;

  private final java.util.function.BooleanSupplier cancellationRequested;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** 予定表。 */
  private final GoogleCalendarService calendar;

  /** タスク一覧。 */
  private final java.util.function.Supplier<String> authorizeTasks;

  private final java.util.function.BiFunction<Integer, java.time.LocalDate, String> listTasks;
  private final java.util.function.Function<TaskAddition, String> addTask;
  private final java.util.function.LongFunction<String> completeTask;
  private final java.util.function.LongFunction<String> deleteTask;

  private record TaskAddition(String title, java.time.LocalDate due, int priority) {}

  /** 作業状況。 */
  private dev.mikoto2000.rei.activity.ActivityCommand activity;

  /** Blueskyへの投稿を依頼する窓口。 */
  private java.util.function.BiFunction<String, java.util.List<String>, String> replyOnBluesky;

  /** 関心一覧。 */
  private dev.mikoto2000.rei.interest.InterestDiscoveryJob interests;

  /** フィードUpdates。 */
  private dev.mikoto2000.rei.feed.FeedUpdateService feedUpdates;

  /** フィード要約一覧。 */
  private dev.mikoto2000.rei.feed.FeedSummaryService feedSummaries;

  /** 検索。 */
  private dev.mikoto2000.rei.core.command.SearchCommand search;

  /** 日次案内。 */
  private dev.mikoto2000.rei.briefing.BriefingService briefing;

  /** 作業。 */
  private dev.mikoto2000.rei.workcontext.WorkContextService work;

  /** 埋め込み表現。 */
  private BiConsumer<java.util.List<String>, java.util.function.Consumer<String>> ingestDocuments;

  private org.springframework.beans.factory.ObjectProvider<
          dev.mikoto2000.rei.secretary.SecretaryConnections>
      secretaryConnections;

  @org.springframework.beans.factory.annotation.Autowired
  public void setSecretaryConnections(
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.secretary.SecretaryConnections>
          connections) {
    secretaryConnections = connections;
  }

  public AgentRunContext authorizeSecretary(
      String project, String session, String connectionId, boolean force) {
    var service =
        java.util.Objects.requireNonNull(secretaryConnections, "Secretary unavailable").getObject();
    var connection = service.status(project, connectionId);
    if (!connection.config().sessionId().equals(session))
      throw new IllegalArgumentException("Connection belongs to another Session");
    return submitAuxiliary(
        project,
        session,
        "secretary.authorize",
        () -> service.authorize(project, connectionId, force).state());
  }

  /** 実行の受付と状態管理で使用する埋め込み表現を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setEmbeddings(
      dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService embeddings) {
    this.ingestDocuments =
        embeddings == null
            ? null
            : (documents, output) -> embeddings.addAndReport(documents, output);
  }

  /** 実行の受付と状態管理で用いるingestベクトル一覧を返す。 */
  public AgentRunContext ingestVectors(
      String project, String session, java.util.List<String> documents) {
    return submitAuxiliary(
        project,
        session,
        "vectors.ingest",
        () -> {
          var context = AgentRunScope.current();
          java.util.Objects.requireNonNull(ingestDocuments, "Embedding unavailable")
              .accept(
                  documents,
                  line ->
                      bus.publish(
                          events
                              .messageDelta(
                                  context.runId(),
                                  CredentialRedactor.redact(line) + System.lineSeparator())
                              .withOwnership(context)));
          return "";
        });
  }

  /** 実行の受付と状態管理で使用する日次案内を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setBriefing(dev.mikoto2000.rei.briefing.BriefingService briefing) {
    this.briefing = briefing;
  }

  /** 実行の受付と状態管理で使用する作業を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setWork(dev.mikoto2000.rei.workcontext.WorkContextService work) {
    this.work = work;
  }

  /** 実行の受付と状態管理で用いる日次案内を返す。 */
  public AgentRunContext briefing(String project, String session) {
    return submitAuxiliary(
        project,
        session,
        "briefing.today",
        () ->
            dev.mikoto2000.rei.briefing.command.BriefingCommand.reportToday(
                java.util.Objects.requireNonNull(briefing, "Briefing unavailable")));
  }

  /** 最新の内容を後続の処理に反映するため、作業を更新する。 */
  public AgentRunContext updateWork(String project, String session) {
    if (session == null) throw new IllegalArgumentException("Session を選択して会話を開始してください。");
    return submitAuxiliary(
        project,
        session,
        "work.update",
        () ->
            java.util.Objects.requireNonNull(work, "Work context unavailable")
                .update(session, null, true)
                .map(
                    context ->
                        "Work Context saved: project="
                            + context.projectId()
                            + " revision="
                            + context.revision())
                .orElse("保存できる会話はまだありません。"));
  }

  /** 実行の受付と状態管理で使用する検索を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setSearch(dev.mikoto2000.rei.core.command.SearchCommand search) {
    this.search = search;
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public AgentRunContext search(
      String project,
      String session,
      String query,
      int vectorTopK,
      int webTopK,
      Double threshold,
      String source) {
    return submitAuxiliary(
        project,
        session,
        "knowledge.search",
        () -> {
          var context = AgentRunScope.current();
          java.util.Objects.requireNonNull(search, "Search unavailable")
              .report(
                  query,
                  vectorTopK,
                  webTopK,
                  threshold,
                  source,
                  chunk ->
                      bus.publish(
                          events
                              .messageDelta(context.runId(), CredentialRedactor.redact(chunk))
                              .withOwnership(context)));
          return "";
        });
  }

  /** 実行の受付と状態管理で使用するフィード一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setFeeds(
      dev.mikoto2000.rei.feed.FeedUpdateService updates,
      dev.mikoto2000.rei.feed.FeedSummaryService summaries) {
    feedUpdates = updates;
    feedSummaries = summaries;
  }

  /** 最新の内容を後続の処理に反映するため、フィード一覧を更新する。 */
  public AgentRunContext updateFeeds(String project, String session, Long id) {
    return submitAuxiliary(
        project,
        session,
        "feed.update",
        () ->
            dev.mikoto2000.rei.feed.command.FeedCommand.reportUpdate(
                java.util.Objects.requireNonNull(feedUpdates, "Feed updates unavailable"), id));
  }

  /** 利用者が要点を把握できるよう、フィード一覧を要約する。 */
  public AgentRunContext summarizeFeeds(String project, String session) {
    return submitAuxiliary(
        project,
        session,
        "feed.summary",
        () ->
            dev.mikoto2000.rei.feed.command.FeedCommand.reportSummary(
                java.util.Objects.requireNonNull(feedSummaries, "Feed summary unavailable")));
  }

  /** 利用者が要点を把握できるよう、フィード項目を要約する。 */
  public AgentRunContext summarizeFeedItem(String project, String session, long id) {
    return submitAuxiliary(
        project,
        session,
        "feed.item.summary",
        () ->
            dev.mikoto2000.rei.feed.command.FeedCommand.reportItemSummary(
                java.util.Objects.requireNonNull(feedSummaries, "Feed summary unavailable"), id));
  }

  /** 実行の受付と状態管理で使用するBlueskyを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setBluesky(dev.mikoto2000.rei.bluesky.BlueskyPostService service) {
    replyOnBluesky =
        service == null
            ? null
            : (target, words) ->
                dev.mikoto2000.rei.bluesky.command.BskyCommand.reportReply(service, target, words);
  }

  /** 実行の受付と状態管理で使用する関心一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setInterests(dev.mikoto2000.rei.interest.InterestDiscoveryJob job) {
    interests = job;
  }

  /** 実行の受付と状態管理で用いる返信Blueskyを返す。 */
  public AgentRunContext replyBluesky(String project, String session, String target, String text) {
    return submitAuxiliary(
        project,
        session,
        "bluesky.reply",
        () ->
            java.util.Objects.requireNonNull(replyOnBluesky, "Bluesky unavailable")
                .apply(target, text == null ? java.util.List.of() : java.util.List.of(text)));
  }

  /** 実行の受付と状態管理で用いる利用先の検出関心一覧を返す。 */
  public AgentRunContext discoverInterests(String project, String session) {
    return submitAuxiliary(
        project,
        session,
        "interest.discover",
        () ->
            dev.mikoto2000.rei.interest.command.InterestCommand.reportDiscovery(
                java.util.Objects.requireNonNull(interests, "Interest discovery unavailable")));
  }

  /** 実行の受付と状態管理で使用する作業状況を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setActivity(dev.mikoto2000.rei.activity.ActivityCommand activity) {
    this.activity = activity;
  }

  /** 指定された値や利用先を使って、IntegrationRunSubmitServiceを初期化する。 */
  public IntegrationRunSubmitService(
      ProjectRegistry projects,
      SessionLifecycle lifecycle,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      CommandCancellationService cancellation,
      AgentEventFactory events,
      AgentEventBus bus,
      GoogleCalendarService calendar,
      TaskService tasks) {
    this.findProject = id -> projects.resolveById(id);
    this.validateSession = (session, project) -> lifecycle.validate(session, project);
    this.admitRun =
        (project, request) ->
            lifecycle.submit(
                project,
                request.session(),
                request.operation(),
                AgentRunContext.RequestSource.WEB,
                AgentRunContext.Mode.EXCLUSIVE,
                false,
                request.key(),
                request.fingerprint(),
                context -> enqueueIntegration(context, request));
    this.registerRun = context -> registry.register(context);
    this.forgetRun = run -> registry.forget(run);
    this.runs = runs;
    this.submitOperation =
        (context, operation) ->
            router.submitOperation(context, operation, work -> runs.execute(context, work));
    this.beginCommand =
        () -> {
          cancellation.begin(Thread.currentThread());
          return cancellation::clear;
        };
    this.cancellationRequested = () -> cancellation.isCancellationRequested();
    this.events = events;
    this.bus = bus;
    this.calendar = calendar;
    this.authorizeTasks = () -> TaskCommand.reportAuthorization(tasks);
    this.listTasks = (priority, due) -> TaskCommand.reportList(tasks, priority, due);
    this.addTask =
        input -> TaskCommand.reportAdd(tasks, input.title(), input.due(), input.priority());
    this.completeTask = id -> TaskCommand.reportDone(tasks, id);
    this.deleteTask = id -> TaskCommand.reportDelete(tasks, id);
  }

  /** 実行の受付と状態管理で用いる権限の確認予定表を返す。 */
  public AgentRunContext authorizeCalendar(String project, String session, String key) {
    return submit(
        project,
        session,
        key,
        "google.calendar.authorize",
        () -> ScheduleCommand.reportAuthorization(calendar));
  }

  /** 実行の受付と状態管理で用いる再確認予定表を返す。 */
  public AgentRunContext refreshCalendar(String project, String session, String key) {
    return submit(
        project,
        session,
        key,
        "google.calendar.refresh",
        () -> ScheduleCommand.reportRefresh(calendar));
  }

  /** 実行の受付と状態管理で用いる権限の確認タスク一覧を返す。 */
  public AgentRunContext authorizeTasks(String project, String session, String key) {
    return submit(project, session, key, "google.tasks.authorize", authorizeTasks);
  }

  /** 利用可能な対象を確認できるよう、Googleタスク一覧を一覧にする。 */
  public AgentRunContext listGoogleTasks(
      String project, String session, Integer priority, java.time.LocalDate dueBefore) {
    return submitAuxiliary(
        project, session, "google.tasks.list", () -> listTasks.apply(priority, dueBefore));
  }

  /** 新たな対象を管理できるよう、Googleタスクを追加する。 */
  public AgentRunContext addGoogleTask(
      String project, String session, String title, java.time.LocalDate due, int priority) {
    return submitAuxiliary(
        project,
        session,
        "google.tasks.add",
        () -> addTask.apply(new TaskAddition(title, due, priority)));
  }

  /** 実行結果を後続の処理へ引き渡すため、Googleタスクを完了させる。 */
  public AgentRunContext completeGoogleTask(String project, String session, long id) {
    return submitAuxiliary(project, session, "google.tasks.done", () -> completeTask.apply(id));
  }

  /** 対象を管理から外すため、Googleタスクを削除する。 */
  public AgentRunContext deleteGoogleTask(String project, String session, long id) {
    return submitAuxiliary(project, session, "google.tasks.delete", () -> deleteTask.apply(id));
  }

  /** 利用可能な対象を確認できるよう、予定表を一覧にする。 */
  public AgentRunContext listCalendar(
      String project, String session, java.time.LocalDate date, String from, String to) {
    return submitAuxiliary(
        project,
        session,
        "google.calendar.list",
        () -> ScheduleCommand.reportList(calendar, date, from, to));
  }

  /** 新たな対象を管理できるよう、予定表イベントを追加する。 */
  public AgentRunContext addCalendarEvent(
      String project,
      String session,
      String title,
      String start,
      String end,
      String location,
      String description) {
    return submitAuxiliary(
        project,
        session,
        "google.calendar.add",
        () -> ScheduleCommand.reportAdd(calendar, title, start, end, location, description));
  }

  /** 実行の受付と状態管理で用いる提案分類規則一覧を返す。 */
  public AgentRunContext suggestClassificationRules(String project, String session) {
    return submitAuxiliary(
        project,
        session,
        "activity.classification.suggest-rules",
        () -> {
          if (activity == null) throw new IllegalStateException("Activity unavailable");
          return activity.reportClassificationRuleSuggestion();
        });
  }

  /** 実行の受付と状態管理で用いる提案作業習慣規則一覧を返す。 */
  public AgentRunContext suggestBehaviorRules(String project, String session) {
    return submitAuxiliary(
        project,
        session,
        "activity.behavior.suggest-rules",
        () -> {
          if (activity == null) throw new IllegalStateException("Activity unavailable");
          return activity.reportBehaviorRuleSuggestion();
        });
  }

  /** 依頼を実行経路へ渡すため、Auxiliaryを受け付ける。 */
  private AgentRunContext submitAuxiliary(
      String projectId,
      String sessionId,
      String operation,
      java.util.function.Supplier<String> report) {
    var project =
        findProject.apply(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    if (sessionId != null) validateSession.accept(sessionId, projectId);
    var context =
        new AgentRunContext(
            java.util.UUID.randomUUID().toString(),
            sessionId == null ? "" : sessionId,
            project.root(),
            project.id(),
            AgentRunContext.RequestSource.WEB);
    registerRun.accept(context);
    try {
      submitOperation.accept(context, () -> execute(context, operation, report));
    } catch (RuntimeException | Error error) {
      forgetRun.accept(context.runId());
      throw error;
    }
    return context;
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  private AgentRunContext submit(
      String projectId,
      String sessionId,
      String key,
      String operation,
      java.util.function.Supplier<String> report) {
    if (key == null
        || !key.matches("[A-Za-z0-9._:-]{1,128}")
        || sessionId != null && (sessionId.isBlank() || sessionId.length() > 512))
      throw new IllegalArgumentException("Invalid receipt key or Session");
    var project =
        findProject.apply(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    String fingerprint;
    try {
      fingerprint =
          dev.mikoto2000.rei.conversation.ConversationAdmissionStore.hash(
              dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(
                  java.util.Arrays.asList(operation, projectId, sessionId)));
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot fingerprint integration request", error);
    }
    return admitRun.apply(project, new Admission(sessionId, operation, key, fingerprint, report));
  }

  private void enqueueIntegration(AgentRunContext context, Admission request) {
    registerRun.accept(context);
    try {
      submitOperation.accept(
          context, () -> execute(context, request.operation(), request.report()));
    } catch (RuntimeException | Error error) {
      forgetRun.accept(context.runId());
      throw error;
    }
  }

  /** 要求された処理を実行する。 */
  private void execute(
      AgentRunContext context, String operation, java.util.function.Supplier<String> report) {
    var scope = AgentRunScope.open(context);
    try (scope) {
      Runnable clearCancellation = beginCommand.get();
      try {
        if (cancellationRequested.getAsBoolean()) {
          runs.cancel(context.runId());
          return;
        }
        bus.publish(events.runStarted(context.runId(), operation, null).withOwnership(context));
        String text = report.get();
        if (cancellationRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
          runs.cancel(context.runId());
          return;
        }
        bus.publish(
            events
                .messageDelta(context.runId(), CredentialRedactor.redact(text))
                .withOwnership(context));
        bus.publish(events.runCompleted(context.runId(), 0).withOwnership(context));
      } catch (java.util.concurrent.CancellationException cancelled) {
        runs.cancel(context.runId());
      } finally {
        clearCancellation.run();
      }
    }
  }
}
