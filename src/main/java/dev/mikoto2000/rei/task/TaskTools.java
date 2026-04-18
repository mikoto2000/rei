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
    return taskService.add(
        title,
        dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate),
        priority,
        tags == null ? List.of() : tags);
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
    return taskService.complete(id);
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
}
