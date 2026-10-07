package dev.mikoto2000.rei.planning;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Clock;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.application.task.TaskManagerService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.workcontext.WorkContextRepository;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.activity.ActivityWorkContextService;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"rei.web.enabled","rei.today.enabled"},havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(DailyPlanningProperties.class)
@Import(dev.mikoto2000.rei.web.DailyPlanningController.class)
public class DailyPlanningConfiguration {
  @Bean DailyPlanningService dailyPlanningService(DailyPlanningProperties properties,Clock clock,ProjectRegistry projects,RunRegistry runs,
      ObjectProvider<TaskManagerService> existing,ObjectProvider<WorkContextRepository> contexts,ObjectProvider<GoalRepository> goals,
      ObjectProvider<PersistentDependencyRepository> dependencies,ObjectProvider<PersistentAgentScheduler> schedules,
      ObjectProvider<PersistentCheckpointRepository> checkpoints,ObjectProvider<ActivityWorkContextService> activity,
      ObjectProvider<dev.mikoto2000.rei.artifact.ArtifactStore> artifacts) {
    var projection=existing.getIfAvailable();
    if(projection==null){projection=new TaskManagerService(projects,runs,checkpoints.getIfAvailable(),goals.getIfAvailable(),dependencies.getIfAvailable(),schedules.getIfAvailable());var store=artifacts.getIfAvailable();if(store!=null)projection.setArtifactStore(store);}
    return new DailyPlanningService(properties,clock,projects,projection,contexts.getIfAvailable(),goals.getIfAvailable(),dependencies.getIfAvailable(),schedules.getIfAvailable(),checkpoints.getIfAvailable(),activity.getIfAvailable());
  }
}
