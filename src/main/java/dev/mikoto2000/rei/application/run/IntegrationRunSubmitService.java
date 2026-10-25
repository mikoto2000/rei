package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.googlecalendar.command.ScheduleCommand;
import dev.mikoto2000.rei.task.TaskService;
import dev.mikoto2000.rei.task.command.TaskCommand;
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
  private final ProjectRegistry projects;

  /** 受付から終了までの管理。 */
  private final SessionLifecycle lifecycle;

  /** 登録台帳。 */
  private final RunRegistry registry;

  /** 実行一覧。 */
  private final RunService runs;

  /** 入力の振り分け。 */
  private final ConversationInputRouter router;

  /** キャンセル。 */
  private final CommandCancellationService cancellation;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** 予定表。 */
  private final GoogleCalendarService calendar;

  /** タスク一覧。 */
  private final TaskService tasks;

  /** 作業状況。 */
  private dev.mikoto2000.rei.activity.ActivityCommand activity;

  /** Blueskyへの投稿を依頼する窓口。 */
  private dev.mikoto2000.rei.bluesky.BlueskyPostService bluesky;

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
  private dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService embeddings;

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
    this.embeddings = embeddings;
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
          java.util.Objects.requireNonNull(embeddings, "Embedding unavailable")
              .addAndReport(
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
    bluesky = service;
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
            dev.mikoto2000.rei.bluesky.command.BskyCommand.reportReply(
                java.util.Objects.requireNonNull(bluesky, "Bluesky unavailable"),
                target,
                text == null ? java.util.List.of() : java.util.List.of(text)));
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
    this.projects = projects;
    this.lifecycle = lifecycle;
    this.registry = registry;
    this.runs = runs;
    this.router = router;
    this.cancellation = cancellation;
    this.events = events;
    this.bus = bus;
    this.calendar = calendar;
    this.tasks = tasks;
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
    return submit(
        project,
        session,
        key,
        "google.tasks.authorize",
        () -> TaskCommand.reportAuthorization(tasks));
  }

  /** 利用可能な対象を確認できるよう、Googleタスク一覧を一覧にする。 */
  public AgentRunContext listGoogleTasks(
      String project, String session, Integer priority, java.time.LocalDate dueBefore) {
    return submitAuxiliary(
        project,
        session,
        "google.tasks.list",
        () -> TaskCommand.reportList(tasks, priority, dueBefore));
  }

  /** 新たな対象を管理できるよう、Googleタスクを追加する。 */
  public AgentRunContext addGoogleTask(
      String project, String session, String title, java.time.LocalDate due, int priority) {
    return submitAuxiliary(
        project,
        session,
        "google.tasks.add",
        () -> TaskCommand.reportAdd(tasks, title, due, priority));
  }

  /** 実行結果を後続の処理へ引き渡すため、Googleタスクを完了させる。 */
  public AgentRunContext completeGoogleTask(String project, String session, long id) {
    return submitAuxiliary(
        project, session, "google.tasks.done", () -> TaskCommand.reportDone(tasks, id));
  }

  /** 対象を管理から外すため、Googleタスクを削除する。 */
  public AgentRunContext deleteGoogleTask(String project, String session, long id) {
    return submitAuxiliary(
        project, session, "google.tasks.delete", () -> TaskCommand.reportDelete(tasks, id));
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
        projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    if (sessionId != null) lifecycle.validate(sessionId, projectId);
    var context =
        new AgentRunContext(
            java.util.UUID.randomUUID().toString(),
            sessionId == null ? "" : sessionId,
            project.root(),
            project.id(),
            AgentRunContext.RequestSource.WEB);
    registry.register(context);
    try {
      router.submitOperation(
          context, () -> execute(context, operation, report), work -> runs.execute(context, work));
    } catch (RuntimeException | Error error) {
      registry.forget(context.runId());
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
        projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    String fingerprint;
    try {
      fingerprint =
          dev.mikoto2000.rei.conversation.ConversationAdmissionStore.hash(
              dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(
                  java.util.Arrays.asList(operation, projectId, sessionId)));
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot fingerprint integration request", error);
    }
    return lifecycle.submit(
        project,
        sessionId,
        operation,
        AgentRunContext.RequestSource.WEB,
        AgentRunContext.Mode.EXCLUSIVE,
        false,
        key,
        fingerprint,
        context -> {
          registry.register(context);
          try {
            router.submitOperation(
                context,
                () -> execute(context, operation, report),
                work -> runs.execute(context, work));
          } catch (RuntimeException | Error error) {
            registry.forget(context.runId());
            throw error;
          }
        });
  }

  /** 要求された処理を実行する。 */
  private void execute(
      AgentRunContext context, String operation, java.util.function.Supplier<String> report) {
    try (var scope = AgentRunScope.open(context)) {
      cancellation.begin(Thread.currentThread());
      try {
        if (cancellation.isCancellationRequested()) {
          runs.cancel(context.runId());
          return;
        }
        bus.publish(events.runStarted(context.runId(), operation, null).withOwnership(context));
        String text = report.get();
        if (cancellation.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
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
        cancellation.clear();
      }
    }
  }
}
