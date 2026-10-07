package dev.mikoto2000.rei.application.task;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.checkpoint.*;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import dev.mikoto2000.rei.web.TaskController;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"rei.web.enabled","rei.task-manager.enabled"},havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@Import(TaskController.class)
public class TaskManagerConfiguration {
  @Bean TaskManagerService taskManagerService(ProjectRegistry projects,RunRegistry runs,PersistentCheckpointRepository checkpoints,
      GoalRepository goals,PersistentDependencyRepository dependencies,PersistentAgentScheduler schedules) {
    return new TaskManagerService(projects,runs,checkpoints,goals,dependencies,schedules);
  }
  @Bean TaskControlService taskControlService(TaskManagerService tasks,ChatSubmitService chats,RunService runs,RunRegistry registry,
      ConversationInputRouter inputs,PersistentCheckpointService checkpoints,GoalLoopService goals,
      PersistentDependencyRepository dependencies,PersistentAgentScheduler schedules) {
    return new TaskControlService(tasks,chats,runs,registry,inputs,checkpoints,goals,dependencies,schedules);
  }
}
