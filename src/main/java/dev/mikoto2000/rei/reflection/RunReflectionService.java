package dev.mikoto2000.rei.reflection;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import dev.mikoto2000.rei.event.TaskCompletedPayload;
import dev.mikoto2000.rei.event.TaskCreatedPayload;
import dev.mikoto2000.rei.event.TaskFailedPayload;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/**
 * 実行振り返りのユースケースを実現する。
 *
 * <p>Event facts only: no additional model call and no inferred root causes.
 */
@Service
public class RunReflectionService {
  /** リポジトリ。 */
  private final RunReflectionRepository repo;

  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** 履歴。 */
  private final ProjectAgentEventStore history;

  /** 購読。 */
  private AgentEventBus.Subscription subscription;

  /** 作業の振り返りを利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RunReflectionService(RunReflectionRepository repo, AgentEventBus bus) {
    this(repo, bus, null);
  }

  /** 指定された値や利用先を使って、RunReflectionServiceを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public RunReflectionService(
      RunReflectionRepository repo, AgentEventBus bus, ProjectAgentEventStore history) {
    this.repo = repo;
    this.bus = bus;
    this.history = history;
  }

  /**
   * 判断に必要な情報を集め、後続の処理へ渡す。
   *
   * <p>Bounded human backfill of facts only. Never starts or verifies the saved Run.
   */
  public synchronized RunReflectionRepository.Item collect(
      String project, String session, String run) {
    if (history == null
        || !RunReflectionRepository.valid(project)
        || !RunReflectionRepository.valid(session)
        || !RunReflectionRepository.valid(run))
      throw new IllegalArgumentException("Project, Session, Run and persisted history required");
    var saved = history.recent(project, 1000);
    if (saved.size() == 1000)
      throw new IllegalArgumentException(
          "History window is full; inspect the persisted event log instead of inferring a complete reflection");
    var owned =
        saved.stream()
            .filter(
                e ->
                    project.equals(e.projectId())
                        && session.equals(e.sessionId())
                        && run.equals(e.runId()))
            .toList();
    var terminal =
        owned.stream()
            .filter(
                e ->
                    e.type() == AgentEventType.AGENT_RUN_COMPLETED
                            && e.payload() instanceof AgentRunCompletedPayload p
                            && matches(e, p.runId())
                        || (e.type() == AgentEventType.AGENT_RUN_FAILED
                                || e.type() == AgentEventType.AGENT_RUN_CANCELLED)
                            && e.payload() instanceof AgentRunFailedPayload failed
                            && matches(e, failed.runId()))
            .reduce((a, b) -> b)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No owned terminal Run fact in the bounded history window"));
    for (var event : owned) {
      if (event.type() == AgentEventType.TOOL_COMPLETED
          || event.type() == AgentEventType.TOOL_FAILED) observe(event);
      if (event.id().equals(terminal.id())) break;
    }
    String status =
        terminal.type() == AgentEventType.AGENT_RUN_COMPLETED
            ? "COMPLETED"
            : terminal.type() == AgentEventType.AGENT_RUN_CANCELLED ? "CANCELLED" : "FAILED";
    return repo.reflect(
        terminal,
        "RUN",
        run,
        status,
        terminal.payload() instanceof AgentRunFailedPayload p ? error(p.error()) : "");
  }

  /** 要求された処理を開始する。 */
  @PostConstruct
  public synchronized void start() {
    if (subscription == null) subscription = bus.subscribe(this::observe);
  }

  /** 作業の振り返りで利用した資源や登録を解放し、利用を終了する。 */
  @PreDestroy
  public synchronized void close() {
    if (subscription != null) {
      subscription.unsubscribe();
      subscription = null;
    }
  }

  /** 状況を判断する根拠を得るため、実行振り返りサービスを観測する。 */
  private synchronized void observe(AgentEvent event) {
    if (!RunReflectionRepository.valid(event.projectId())
        || !RunReflectionRepository.valid(event.sessionId())) return;
    switch (event.type()) {
      case TOOL_COMPLETED -> {
        if (event.payload() instanceof ToolCompletedPayload payload)
          repo.action(
              event,
              new RunReflectionRepository.Action(
                  payload.toolCallId(), payload.toolName(), "COMPLETED", ""));
      }
      case TOOL_FAILED -> {
        if (event.payload() instanceof ToolFailedPayload payload)
          repo.action(
              event,
              new RunReflectionRepository.Action(
                  payload.toolCallId(), payload.toolName(), "FAILED", error(payload.error())));
      }
      case TASK_CREATED -> {
        if (event.payload() instanceof TaskCreatedPayload payload) repo.task(event, payload);
      }
      case TASK_COMPLETED -> {
        if (event.payload() instanceof TaskCompletedPayload payload
            && RunReflectionRepository.valid(payload.taskId()))
          repo.reflect(event, "TASK", payload.taskId(), "COMPLETED", "");
      }
      case TASK_FAILED -> {
        if (event.payload() instanceof TaskFailedPayload payload
            && RunReflectionRepository.valid(payload.taskId()))
          repo.reflect(event, "TASK", payload.taskId(), "FAILED", error(payload.error()));
      }
      case AGENT_RUN_COMPLETED -> {
        if (event.payload() instanceof AgentRunCompletedPayload payload
            && matches(event, payload.runId()))
          repo.reflect(event, "RUN", payload.runId(), "COMPLETED", "");
      }
      case AGENT_RUN_FAILED, AGENT_RUN_CANCELLED -> {
        if (event.payload() instanceof AgentRunFailedPayload payload
            && matches(event, payload.runId()))
          repo.reflect(
              event,
              "RUN",
              payload.runId(),
              event.type() == AgentEventType.AGENT_RUN_CANCELLED ? "CANCELLED" : "FAILED",
              error(payload.error()));
      }
      default -> {}
    }
  }

  /** 指定された条件と対象が一致するかを判定する。 */
  private boolean matches(AgentEvent event, String id) {
    return RunReflectionRepository.valid(id) && id.equals(event.runId());
  }

  /** 作業の振り返りで用いるエラーを返す。 */
  private String error(ErrorInformation error) {
    return error == null ? "not_recorded" : RunReflectionRepository.safe(error.errorType(), 128);
  }
}
