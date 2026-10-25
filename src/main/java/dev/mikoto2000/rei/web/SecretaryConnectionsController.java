package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.secretary.SecretaryConnections;
import dev.mikoto2000.rei.secretary.SecretaryProvider;
import dev.mikoto2000.rei.secretary.SecretaryTask;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Human operations protected by the existing bearer authentication and strict request decoder. */
@RestController
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.secretary.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/secretary")
public class SecretaryConnectionsController {
  private final org.springframework.beans.factory.ObjectProvider<SecretaryConnections> services;

  public SecretaryConnectionsController(
      org.springframework.beans.factory.ObjectProvider<SecretaryConnections> services) {
    this.services = services;
  }

  private SecretaryConnections service() {
    return services.getObject();
  }

  public record Configure(SecretaryConnections.Config config, Long version)
      implements StrictApiRequest {}

  public record Control(String projectId, Long revision) implements StrictApiRequest {}

  public record Read(String projectId, String operation, Map<String, String> arguments)
      implements StrictApiRequest {
    public Read {
      arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
  }

  public record Convert(String projectId, String externalId) implements StrictApiRequest {}

  public record Prepare(
      String projectId,
      String connectionId,
      String key,
      String operation,
      Map<String, String> arguments)
      implements StrictApiRequest {
    public Prepare {
      arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
  }

  @GetMapping("/connections")
  public List<SecretaryConnections.Connection> list(@RequestParam String projectId) {
    return service().list(projectId);
  }

  @PutMapping("/connections")
  public SecretaryConnections.Connection configure(@RequestBody Configure r) {
    if (r.version() == null)
      throw new IllegalArgumentException("Configuration version required (-1 for new)");
    return service().configure(r.config(), r.version());
  }

  @GetMapping("/connections/{id}")
  public SecretaryConnections.Connection status(
      @RequestParam String projectId, @PathVariable String id) {
    return service().status(projectId, id);
  }

  @GetMapping("/connections/{id}/auth")
  public SecretaryProvider.Auth auth(@RequestParam String projectId, @PathVariable String id) {
    return service().authStatus(projectId, id);
  }

  @PostMapping("/connections/{id}/poll")
  public SecretaryConnections.Connection poll(@PathVariable String id, @RequestBody Control r) {
    return service().pollNow(r.projectId(), id);
  }

  @PostMapping("/connections/{id}/disconnect")
  public SecretaryConnections.Connection disconnect(
      @PathVariable String id, @RequestBody Control r) {
    if (r.revision() == null) throw new IllegalArgumentException("Configuration version required");
    return service().disconnect(r.projectId(), id, r.revision(), false);
  }

  @PostMapping("/connections/{id}/read")
  public SecretaryProvider.Page read(@PathVariable String id, @RequestBody Read r) {
    return service().read(r.projectId(), id, r.operation(), r.arguments());
  }

  @GetMapping("/connections/{id}/items")
  public List<SecretaryProvider.Item> items(
      @RequestParam String projectId,
      @PathVariable String id,
      @RequestParam(defaultValue = "100") int limit) {
    return service().items(projectId, id, limit);
  }

  @GetMapping("/connections/{id}/history")
  public List<SecretaryConnections.Event> history(
      @RequestParam String projectId,
      @PathVariable String id,
      @RequestParam(defaultValue = "100") int limit) {
    return service().history(projectId, id, limit);
  }

  @PostMapping("/connections/{id}/tasks")
  public SecretaryTask task(@PathVariable String id, @RequestBody Convert r) {
    return service().toTask(r.projectId(), id, r.externalId());
  }

  @PostMapping("/external-actions")
  public SecretaryConnections.Action prepare(@RequestBody Prepare r) {
    return service()
        .prepare(r.projectId(), r.connectionId(), r.key(), r.operation(), r.arguments());
  }

  @GetMapping("/external-actions")
  public List<SecretaryConnections.Action> actions(
      @RequestParam String projectId, @RequestParam(defaultValue = "100") int limit) {
    return service().actions(projectId, limit);
  }

  @GetMapping("/external-actions/{id}")
  public SecretaryConnections.Action action(
      @RequestParam String projectId, @PathVariable String id) {
    return service().action(projectId, id);
  }

  @GetMapping("/external-actions/{id}/history")
  public List<SecretaryConnections.ActionHistory> actionHistory(
      @RequestParam String projectId,
      @PathVariable String id,
      @RequestParam(defaultValue = "100") int limit) {
    return service().actionHistory(projectId, id, limit);
  }

  @PostMapping("/external-actions/{id}/{operation}")
  public SecretaryConnections.Action control(
      @PathVariable String id, @PathVariable String operation, @RequestBody Control r) {
    if (r.revision() == null) throw new IllegalArgumentException("Action revision required");
    return switch (operation) {
      case "execute" -> service().execute(r.projectId(), id, r.revision());
      case "verify" -> service().verify(r.projectId(), id, r.revision());
      case "cancel" -> service().cancel(r.projectId(), id, r.revision());
      default -> throw new IllegalArgumentException("Unknown external action control");
    };
  }
}
