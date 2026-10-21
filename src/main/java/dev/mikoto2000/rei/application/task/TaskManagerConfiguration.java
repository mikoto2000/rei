package dev.mikoto2000.rei.application.task;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.goal.GoalLoopService;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import dev.mikoto2000.rei.web.TaskController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** タスク管理の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.task-manager.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(TaskController.class)
public class TaskManagerConfiguration {
  /** 実行中タスクの操作で用いるタスク管理サービスを返す。 */
  @Bean
  TaskManagerService taskManagerService(
      ProjectRegistry projects,
      RunRegistry runs,
      PersistentCheckpointRepository checkpoints,
      GoalRepository goals,
      PersistentDependencyRepository dependencies,
      PersistentAgentScheduler schedules) {
    return new TaskManagerService(projects, runs, checkpoints, goals, dependencies, schedules);
  }

  /** 実行中タスクの操作で用いるタスク制御サービスを返す。 */
  @Bean
  TaskControlService taskControlService(
      TaskManagerService tasks,
      ChatSubmitService chats,
      RunService runs,
      RunRegistry registry,
      ConversationInputRouter inputs,
      PersistentCheckpointService checkpoints,
      GoalLoopService goals,
      PersistentDependencyRepository dependencies,
      PersistentAgentScheduler schedules) {
    return new TaskControlService(
        tasks, chats, runs, registry, inputs, checkpoints, goals, dependencies, schedules);
  }
}
