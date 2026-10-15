package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.subagent.*;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

/** Fixed definition management only; this API does not launch or delegate to an agent. */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/subagents")
public final class SubAgentController {
  private final SubAgentCommand command;

  public record Text(String text) {}

  public record Identity(String id) implements StrictApiRequest {}

  public record FileRequest(String file) implements StrictApiRequest {}

  public SubAgentController(
      SubAgentRegistry registry, SubAgentDefinitionLoader loader, SubAgentToolPolicy policy) {
    command = new SubAgentCommand(registry, loader, policy);
  }

  @GetMapping
  public Text list() {
    return new Text(command.renderList());
  }

  @GetMapping("/{id}")
  public Text show(@PathVariable String id) {
    return new Text(command.renderShow(id));
  }

  @PostMapping("/reload")
  public Text reload() {
    return new Text(command.renderReload());
  }

  @PostMapping("/validate")
  public Text validate(@RequestBody FileRequest request) {
    if (request == null
        || request.file() == null
        || request.file().isBlank()
        || request.file().length() > 4096
        || request.file().codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Definition file required");
    return new Text(command.renderValidate(Path.of(request.file())));
  }

  @PostMapping("/initialize")
  public Text initialize(@RequestBody Identity request) {
    if (request == null || request.id() == null || request.id().length() > 128)
      throw new IllegalArgumentException("Definition identity required");
    return new Text(command.renderInitialize(request.id()));
  }
}
