package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import dev.mikoto2000.rei.skills.command.SkillCommand;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/skills")
public final class SkillReportController {
  private final SkillCommand command;
  public record Text(String text) {}
  public SkillReportController(AgentSkillRepository repository){command=new SkillCommand(repository);}
  @GetMapping("/report") public Text list(){return new Text(command.renderList());}
  @GetMapping("/{name}/report") public Text show(@PathVariable String name){if(name==null||name.isBlank()||name.length()>128)throw new IllegalArgumentException("Skill name required");return new Text(command.renderShow(name));}
  @PostMapping("/reload-report") public Text reload(){return new Text(command.renderReload());}
}
