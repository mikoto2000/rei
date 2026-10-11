package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.ui.shell.ActiveRunDisplay;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public final class ActiveRunController {
  private final ActiveRunDisplay display;
  private final CommandCancellationService cancellation;
  private final ProjectRegistry projects;
  public record Text(String text) {}
  public record Cancellation(boolean accepted) {}
  public ActiveRunController(ActiveRunDisplay display,CommandCancellationService cancellation,ProjectRegistry projects){this.display=display;this.cancellation=cancellation;this.projects=projects;}
  @GetMapping("/api/v1/runs/active") public Text list() {
    var rows=display.rows();return new Text("Active Executions\n"+(rows.isEmpty()?"No active runs.":"PROJECT  TYPE  STATUS  ELAPSED  REQUEST\n"+String.join("\n",rows)));
  }
  @PostMapping("/api/v1/projects/{projectId}/runs/cancel") public Cancellation cancel(@PathVariable String projectId) {
    if(projectId==null||projectId.isBlank()||projectId.length()>128)throw new IllegalArgumentException("Project ID required");
    var project=projects.resolveById(projectId).orElseThrow(()->new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    return new Cancellation(cancellation.cancelProject(project.id()));
  }
}
