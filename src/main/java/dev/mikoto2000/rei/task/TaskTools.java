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
    return taskService.listOpen();
  }

  @Tool(name = "taskCreate", description = "タスクを作成します。dueDate は yyyy-MM-dd 形式です。")
  Task taskCreate(String title, String dueDate, int priority, List<String> tags) {
    return taskService.add(
        title,
        dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate),
        priority,
        tags == null ? List.of() : tags);
  }

  @Tool(name = "taskComplete", description = "タスクを完了にします")
  Task taskComplete(long id) {
    return taskService.complete(id);
  }

  @Tool(name = "taskUpdateDeadline", description = "タスクの期限を更新します。dueDate は yyyy-MM-dd 形式です。null または空文字で期限をクリアします。")
  Task taskUpdateDeadline(long id, String dueDate) {
    return taskService.updateDeadline(
        id,
        dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate));
  }
}
