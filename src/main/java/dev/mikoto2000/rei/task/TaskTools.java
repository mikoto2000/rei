package dev.mikoto2000.rei.task;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** タスクをモデルから呼び出せるツールとして提供する。 */
@Component
@RequiredArgsConstructor
public class TaskTools {

  /** タスクサービス。 */
  private final TaskService taskService;

  /** 通知先。 */
  private dev.mikoto2000.rei.event.AgentEventPublisher publisher;

  /** イベント一覧。 */
  private dev.mikoto2000.rei.event.AgentEventFactory events;

  /** タスクの管理で使用するイベント一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setEvents(
      dev.mikoto2000.rei.event.AgentEventPublisher publisher,
      dev.mikoto2000.rei.event.AgentEventFactory events) {
    this.publisher = publisher;
    this.events = events;
  }

  /** 状態や結果を購読先へ通知する。 */
  private void publish(java.util.function.Supplier<dev.mikoto2000.rei.event.AgentEvent> event) {
    if (publisher != null && events != null)
      try {
        publisher.publish(event.get());
      } catch (RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Task lifecycle delivery failed: {}", failure.getClass().getSimpleName());
      }
  }

  /** タスクの管理で用いるタスク一覧を返す。 */
  @Tool(name = "taskList", description = "未完了タスクを一覧します")
  List<Task> taskList() {
    IO.println("未完了タスクを一覧するよ");
    return taskService.listOpen();
  }

  /** タスクの管理で用いるタスク作成を返す。 */
  @Tool(name = "taskCreate", description = "タスクを作成します。dueDate は yyyy-MM-dd 形式です。")
  Task taskCreate(String title, String dueDate, int priority) {
    IO.println(String.format("タスク %s を作成するよ。期限=%s、優先度=%d", title, dueDate, priority));
    try {
      Task created =
          taskService.add(
              title,
              dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate),
              priority);
      publish(
          () ->
              events.taskCreated(
                  Long.toString(created.id()), null, created.title(), created.status().name()));
      return created;
    } catch (RuntimeException e) {
      IO.println("[error] " + userFacingMessage(e, "Google Tasks へのタスク追加に失敗しました"));
      throw e;
    }
  }

  /** タスクの管理で用いるタスク更新を返す。 */
  @Tool(
      name = "taskUpdate",
      description =
          "タスクを更新します。title, dueDate, priority のうち必要な項目だけ指定できます。dueDate は yyyy-MM-dd 形式です。")
  Task taskUpdate(long id, String title, String dueDate, Integer priority) {
    IO.println(
        String.format(
            "タスク %d を更新するよ。title=%s、dueDate=%s、priority=%s", id, title, dueDate, priority));
    return taskService.update(
        id,
        title,
        dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate),
        priority);
  }

  /** タスクの管理で用いるタスク完了を返す。 */
  @Tool(name = "taskComplete", description = "タスクを完了にします")
  Task taskComplete(long id) {
    IO.println(String.format("タスク %d を完了にするよ", id));
    publish(() -> events.taskStarted(Long.toString(id)));
    try {
      Task completed = taskService.complete(id);
      if (completed == null || completed.id() != id || completed.status() != TaskStatus.DONE)
        throw new IllegalStateException("Task completion was not confirmed");
      publish(() -> events.taskCompleted(Long.toString(id), null));
      return completed;
    } catch (RuntimeException failure) {
      publish(
          () ->
              events.taskFailed(
                  Long.toString(id),
                  new dev.mikoto2000.rei.event.ErrorInformation(
                      failure.getClass().getSimpleName(),
                      "Task completion operation failed",
                      null)));
      throw failure;
    }
  }

  /** タスクの管理で用いるタスク更新期限を返す。 */
  @Tool(
      name = "taskUpdateDeadline",
      description = "タスクの期限を更新します。dueDate は yyyy-MM-dd 形式です。null または空文字で期限をクリアします。")
  Task taskUpdateDeadline(long id, String dueDate) {
    IO.println(String.format("タスク %d の期限を %s に更新するよ", id, dueDate));
    return taskService.updateDeadline(
        id, dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate));
  }

  /** タスクの管理に関するタスク削除を処理する。 */
  @Tool(name = "taskDelete", description = "タスクを削除します")
  void taskDelete(long id) {
    IO.println(String.format("タスク %d を削除するよ", id));
    taskService.delete(id);
  }

  /** タスクの管理で用いる利用者Facingメッセージを返す。 */
  private String userFacingMessage(Throwable error, String fallback) {
    Throwable root = rootCause(error);
    String message = root.getMessage();
    if (message == null || message.isBlank()) {
      message = error.getMessage();
    }
    if (message == null || message.isBlank()) {
      return fallback;
    }
    if (fallback.equals(message) || message.startsWith(fallback + ":")) {
      return message;
    }
    return fallback + ": " + message;
  }

  /** タスクの管理で用いる作業ルート原因を返す。 */
  private Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
