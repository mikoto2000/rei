package dev.mikoto2000.rei.task;

import java.time.LocalDate;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TaskTools {

  private final TaskService taskService;
  private dev.mikoto2000.rei.event.AgentEventPublisher publisher;
  private dev.mikoto2000.rei.event.AgentEventFactory events;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setEvents(dev.mikoto2000.rei.event.AgentEventPublisher publisher,dev.mikoto2000.rei.event.AgentEventFactory events){this.publisher=publisher;this.events=events;}
  private void publish(java.util.function.Supplier<dev.mikoto2000.rei.event.AgentEvent> event) {
    if(publisher!=null&&events!=null)try{publisher.publish(event.get());}
    catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Task lifecycle delivery failed: {}",failure.getClass().getSimpleName());}
  }

  @Tool(name = "taskList", description = "未完了タスクを一覧します")
  List<Task> taskList() {
    IO.println("未完了タスクを一覧するよ");
    return taskService.listOpen();
  }

  @Tool(name = "taskCreate", description = "タスクを作成します。dueDate は yyyy-MM-dd 形式です。")
  Task taskCreate(String title, String dueDate, int priority, List<String> tags) {
    IO.println(String.format("タスク %s を作成するよ。期限=%s、優先度=%d、タグ=%s",
        title,
        dueDate,
        priority,
        tags == null ? List.of() : tags));
    try {
      Task created=taskService.add(
          title,
          dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate),
          priority,
          tags == null ? List.of() : tags);
      publish(()->events.taskCreated(Long.toString(created.id()),null,created.title(),created.status().name()));
      return created;
    } catch (RuntimeException e) {
      IO.println("[error] " + userFacingMessage(e, "Google Tasks へのタスク追加に失敗しました"));
      throw e;
    }
  }

  @Tool(name = "taskUpdate", description = "タスクを更新します。title, dueDate, priority, tags のうち必要な項目だけ指定できます。dueDate は yyyy-MM-dd 形式です。")
  Task taskUpdate(long id, String title, String dueDate, Integer priority, List<String> tags) {
    IO.println(String.format("タスク %d を更新するよ。title=%s、dueDate=%s、priority=%s、tags=%s",
        id, title, dueDate, priority, tags));
    return taskService.update(
        id,
        title,
        dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate),
        priority,
        tags);
  }

  @Tool(name = "taskComplete", description = "タスクを完了にします")
  Task taskComplete(long id) {
    IO.println(String.format("タスク %d を完了にするよ", id));
    publish(()->events.taskStarted(Long.toString(id)));
    try {
      Task completed=taskService.complete(id);
      if(completed==null||completed.id()!=id||completed.status()!=TaskStatus.DONE)throw new IllegalStateException("Task completion was not confirmed");
      publish(()->events.taskCompleted(Long.toString(id),null));return completed;
    }catch(RuntimeException failure) {
      publish(()->events.taskFailed(Long.toString(id),new dev.mikoto2000.rei.event.ErrorInformation(failure.getClass().getSimpleName(),"Task completion operation failed",null)));
      throw failure;
    }
  }

  @Tool(name = "taskUpdateDeadline", description = "タスクの期限を更新します。dueDate は yyyy-MM-dd 形式です。null または空文字で期限をクリアします。")
  Task taskUpdateDeadline(long id, String dueDate) {
    IO.println(String.format("タスク %d の期限を %s に更新するよ", id, dueDate));
    return taskService.updateDeadline(
        id,
        dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate));
  }

  @Tool(name = "taskDelete", description = "タスクを削除します")
  void taskDelete(long id) {
    IO.println(String.format("タスク %d を削除するよ", id));
    taskService.delete(id);
  }

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

  private Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}