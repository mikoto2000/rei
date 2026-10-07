package dev.mikoto2000.rei.web;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import dev.mikoto2000.rei.planning.DailyPlanningService;
@RestController
@ConditionalOnProperty(name={"rei.web.enabled","rei.today.enabled"},havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class DailyPlanningController {
  private final DailyPlanningService service;
  public DailyPlanningController(DailyPlanningService service){this.service=service;}
  @GetMapping("/api/v1/today") public DailyPlanningService.Plan today(@RequestParam(required=false,name="projectId") List<String> projectIds){return service.today(projectIds);}
}
