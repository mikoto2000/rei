package dev.mikoto2000.rei.web;

import java.time.Duration;
import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Web APIによる操作に必要なシェルレポート助言を扱う。
 *
 * <p>A CLI-only representation of fixed domain results; execution and Web JSON contracts stay in
 * their controllers.
 */
@ControllerAdvice(
    assignableTypes = {
      GoalController.class,
      AttentionController.class,
      DependencyController.class,
      ScheduleController.class,
      CheckpointController.class
    })
public final class ShellReportAdvice implements ResponseBodyAdvice<Object> {
  /** 依存条件事実一覧。 */
  private dev.mikoto2000.rei.core.dependency.DependencyObservationService dependencyFacts;

  /** Web APIによる操作で使用する依存条件事実一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setDependencyFacts(
      dev.mikoto2000.rei.core.dependency.DependencyObservationService facts) {
    dependencyFacts = facts;
  }

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す実行テキストを表す。
   *
   * @param text テキスト
   * @param runId 実行の識別子
   * @param sessionId 後続の入力を送る会話の識別子
   */
  public record RunText(String text, String runId, String sessionId) {}

  /** Web APIによる操作において、シェルレポート助言への対応可否を返す。 */
  public boolean supports(
      MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
    return java.util.Set.of(
            GoalController.class,
            AttentionController.class,
            DependencyController.class,
            ScheduleController.class,
            CheckpointController.class)
        .contains(method.getContainingClass());
  }

  /** 本処理に必要な文脈や条件を整えるため、本文書き込みを準備する。 */
  public Object beforeBodyWrite(
      Object body,
      MethodParameter method,
      MediaType type,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (!"true".equals(request.getHeaders().getFirst("X-Rei-Shell-Output"))) return body;
    if (method.getContainingClass() == DependencyController.class
        && java.util.Set.of("list", "show", "history").contains(method.getMethod().getName())
        && dependencyFacts != null) dependencyFacts.flushFacts();
    if (body instanceof Text || body instanceof CheckpointController.Text) return body;
    if (body
        instanceof dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.ResumeResult result)
      return new RunText(
          dev.mikoto2000.rei.event.CredentialRedactor.redact(result.toString()),
          result.runId(),
          result.sessionId());
    String name = method.getMethod().getName(), text;
    if (method.getContainingClass() == CheckpointController.class
        && name.equals("save")
        && body instanceof dev.mikoto2000.rei.checkpoint.PersistentCheckpoint state)
      text = "Checkpoint saved: task=" + state.taskId() + " revision=" + state.revision();
    else if (method.getContainingClass() == AttentionController.class && name.equals("acknowledge"))
      text = "Acknowledged. This does not approve, resume or execute the task.";
    else if (body instanceof GoalController.History history)
      text = history.history() + "\nAttempts: " + history.attempts();
    else if (method.getContainingClass() == ScheduleController.class && name.equals("activate"))
      text =
          "Schedule activated; dispatch requires rei.agent-scheduler.enabled and rei.tool-permission.enabled";
    else if (method.getContainingClass() == ScheduleController.class && name.equals("cancel"))
      text = "Schedule cancelled";
    else if (body instanceof ScheduleController.Inspection inspection) {
      var interval =
          inspection.interval() == null
              ? null
              : new dev.mikoto2000.rei.temporal.PersistentAgentScheduler.Interval(
                  Duration.ofMillis(inspection.interval().intervalMillis()),
                  inspection.interval().remaining());
      text =
          inspection.schedule()
              + " interval="
              + Optional.ofNullable(interval)
              + " cron="
              + Optional.ofNullable(inspection.cron())
              + " event="
              + Optional.ofNullable(inspection.eventTrigger());
    } else text = String.valueOf(body);
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(text));
  }
}
