package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.secretary.SecretaryLedger;
import dev.mikoto2000.rei.secretary.SecretaryTask;
import java.time.LocalDate;
import java.util.List;
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

/** Uses the existing API authentication; independent of execution task endpoints. */
@RestController
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.secretary.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/secretary/tasks")
public class SecretaryController {
  private final SecretaryLedger ledger;

  public SecretaryController(SecretaryLedger ledger) {
    this.ledger = ledger;
  }

  @org.springframework.web.bind.annotation.ExceptionHandler(
      dev.mikoto2000.rei.secretary.SecretaryConflictException.class)
  public org.springframework.http.ResponseEntity<Void> conflict() {
    return org.springframework.http.ResponseEntity.status(409).build();
  }

  public record Write(
      String projectId,
      String key,
      String title,
      SecretaryTask.Intent intent,
      SecretaryTask.Status status,
      LocalDate due,
      int priority,
      List<String> tags,
      List<String> references,
      long revision)
      implements StrictApiRequest {
    public Write {
      tags = tags == null ? List.of() : List.copyOf(tags);
      references = references == null ? List.of() : List.copyOf(references);
    }
  }

  public record Extract(String projectId, String eventId, String text)
      implements StrictApiRequest {}

  public record Transition(String projectId, long revision, SecretaryTask.Status status)
      implements StrictApiRequest {}

  @GetMapping
  public List<SecretaryTask> list(
      @RequestParam String projectId,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "100") int limit) {
    return ledger.list(projectId, offset, limit);
  }

  @GetMapping("/{id}")
  public SecretaryTask get(@PathVariable String id, @RequestParam String projectId) {
    return ledger.get(projectId, id);
  }

  @GetMapping("/{id}/sources")
  public List<String> sources(@PathVariable String id, @RequestParam String projectId) {
    return ledger.sources(projectId, id);
  }

  @GetMapping("/{id}/history")
  public List<SecretaryTask> history(@PathVariable String id, @RequestParam String projectId) {
    return ledger.history(projectId, id);
  }

  @GetMapping("/{id}/related")
  public List<SecretaryTask> related(@PathVariable String id, @RequestParam String projectId) {
    return ledger.related(projectId, id);
  }

  @PostMapping
  public SecretaryTask create(@RequestBody Write request) {
    return ledger.create(
        request.projectId(),
        request.key(),
        request.title(),
        request.intent(),
        request.due(),
        request.priority(),
        request.tags(),
        request.references());
  }

  @PostMapping("/extract")
  public List<SecretaryTask> extract(@RequestBody Extract request) {
    return ledger.extract(request.projectId(), request.eventId(), request.text());
  }

  @PutMapping("/{id}")
  public SecretaryTask update(@PathVariable String id, @RequestBody Write request) {
    return ledger.update(
        request.projectId(),
        id,
        request.revision(),
        request.title(),
        request.intent(),
        request.status(),
        request.due(),
        request.priority(),
        request.tags(),
        request.references());
  }

  @PostMapping("/{id}/transition")
  public SecretaryTask transition(@PathVariable String id, @RequestBody Transition request) {
    return ledger.transition(request.projectId(), id, request.revision(), request.status());
  }
}
