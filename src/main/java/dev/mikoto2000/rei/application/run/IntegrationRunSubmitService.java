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
 * Explicit integration operations; no command text, reflection target or service selector is
 * accepted.
 */
@Service
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class IntegrationRunSubmitService {
  private final ProjectRegistry projects;
  private final SessionLifecycle lifecycle;
  private final RunRegistry registry;
  private final RunService runs;
  private final ConversationInputRouter router;
  private final CommandCancellationService cancellation;
  private final AgentEventFactory events;
  private final AgentEventBus bus;
  private final GoogleCalendarService calendar;
  private final TaskService tasks;
  private dev.mikoto2000.rei.activity.ActivityCommand activity;
  private dev.mikoto2000.rei.bluesky.BlueskyPostService bluesky;
  private dev.mikoto2000.rei.interest.InterestDiscoveryJob interests;
  private dev.mikoto2000.rei.feed.FeedUpdateService feedUpdates;
  private dev.mikoto2000.rei.feed.FeedSummaryService feedSummaries;
  private dev.mikoto2000.rei.core.command.SearchCommand search;
  private dev.mikoto2000.rei.briefing.BriefingService briefing;
  private dev.mikoto2000.rei.workcontext.WorkContextService work;
  private dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService embeddings;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setEmbeddings(
      dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService embeddings) {
    this.embeddings = embeddings;
  }

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

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setBriefing(dev.mikoto2000.rei.briefing.BriefingService briefing) {
    this.briefing = briefing;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setWork(dev.mikoto2000.rei.workcontext.WorkContextService work) {
    this.work = work;
  }

  public AgentRunContext briefing(String project, String session) {
    return submitAuxiliary(
        project,
        session,
        "briefing.today",
        () ->
            dev.mikoto2000.rei.briefing.command.BriefingCommand.reportToday(
                java.util.Objects.requireNonNull(briefing, "Briefing unavailable")));
  }

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

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setSearch(dev.mikoto2000.rei.core.command.SearchCommand search) {
    this.search = search;
  }

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

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setFeeds(
      dev.mikoto2000.rei.feed.FeedUpdateService updates,
      dev.mikoto2000.rei.feed.FeedSummaryService summaries) {
    feedUpdates = updates;
    feedSummaries = summaries;
  }

  public AgentRunContext updateFeeds(String project, String session, Long id) {
    return submitAuxiliary(
        project,
        session,
        "feed.update",
        () ->
            dev.mikoto2000.rei.feed.command.FeedCommand.reportUpdate(
                java.util.Objects.requireNonNull(feedUpdates, "Feed updates unavailable"), id));
  }

  public AgentRunContext summarizeFeeds(String project, String session) {
    return submitAuxiliary(
        project,
        session,
        "feed.summary",
        () ->
            dev.mikoto2000.rei.feed.command.FeedCommand.reportSummary(
                java.util.Objects.requireNonNull(feedSummaries, "Feed summary unavailable")));
  }

  public AgentRunContext summarizeFeedItem(String project, String session, long id) {
    return submitAuxiliary(
        project,
        session,
        "feed.item.summary",
        () ->
            dev.mikoto2000.rei.feed.command.FeedCommand.reportItemSummary(
                java.util.Objects.requireNonNull(feedSummaries, "Feed summary unavailable"), id));
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setBluesky(dev.mikoto2000.rei.bluesky.BlueskyPostService service) {
    bluesky = service;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setInterests(dev.mikoto2000.rei.interest.InterestDiscoveryJob job) {
    interests = job;
  }

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

  public AgentRunContext discoverInterests(String project, String session) {
    return submitAuxiliary(
        project,
        session,
        "interest.discover",
        () ->
            dev.mikoto2000.rei.interest.command.InterestCommand.reportDiscovery(
                java.util.Objects.requireNonNull(interests, "Interest discovery unavailable")));
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setActivity(dev.mikoto2000.rei.activity.ActivityCommand activity) {
    this.activity = activity;
  }

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

  public AgentRunContext authorizeCalendar(String project, String session, String key) {
    return submit(
        project,
        session,
        key,
        "google.calendar.authorize",
        () -> ScheduleCommand.reportAuthorization(calendar));
  }

  public AgentRunContext refreshCalendar(String project, String session, String key) {
    return submit(
        project,
        session,
        key,
        "google.calendar.refresh",
        () -> ScheduleCommand.reportRefresh(calendar));
  }

  public AgentRunContext authorizeTasks(String project, String session, String key) {
    return submit(
        project,
        session,
        key,
        "google.tasks.authorize",
        () -> TaskCommand.reportAuthorization(tasks));
  }

  public AgentRunContext listGoogleTasks(
      String project, String session, Integer priority, java.time.LocalDate dueBefore) {
    return submitAuxiliary(
        project,
        session,
        "google.tasks.list",
        () -> TaskCommand.reportList(tasks, priority, dueBefore));
  }

  public AgentRunContext addGoogleTask(
      String project, String session, String title, java.time.LocalDate due, int priority) {
    return submitAuxiliary(
        project,
        session,
        "google.tasks.add",
        () -> TaskCommand.reportAdd(tasks, title, due, priority));
  }

  public AgentRunContext completeGoogleTask(String project, String session, long id) {
    return submitAuxiliary(
        project, session, "google.tasks.done", () -> TaskCommand.reportDone(tasks, id));
  }

  public AgentRunContext deleteGoogleTask(String project, String session, long id) {
    return submitAuxiliary(
        project, session, "google.tasks.delete", () -> TaskCommand.reportDelete(tasks, id));
  }

  public AgentRunContext listCalendar(
      String project, String session, java.time.LocalDate date, String from, String to) {
    return submitAuxiliary(
        project,
        session,
        "google.calendar.list",
        () -> ScheduleCommand.reportList(calendar, date, from, to));
  }

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
