package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.autonomous.AutonomousConflictException;
import dev.mikoto2000.rei.autonomous.AutonomousDraft;
import dev.mikoto2000.rei.autonomous.AutonomousDraftStore;
import dev.mikoto2000.rei.autonomous.AutonomousGenerationService;
import dev.mikoto2000.rei.autonomous.AutonomousPublicSources;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Draft review and explicitly selected publication under the existing API authentication. */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/autonomous")
public final class AutonomousController {
  private final AutonomousDraftStore drafts;
  private final AutonomousPublicSources sources;
  private final Supplier<AutonomousGenerationService> generation;
  private final Supplier<AutonomousPublicationService> publication;

  public AutonomousController(
      AutonomousDraftStore drafts,
      AutonomousPublicSources sources,
      ObjectProvider<AutonomousGenerationService> generation,
      ObjectProvider<AutonomousPublicationService> publication) {
    this.drafts = drafts;
    this.sources = sources;
    this.generation = generation::getIfAvailable;
    this.publication = publication::getIfAvailable;
  }

  public record GenerationRequest() implements StrictApiRequest {}

  public record Revision(int expectedRevision) implements StrictApiRequest {}

  public record Edit(int expectedRevision, String text) implements StrictApiRequest {}

  public record Rejection(int expectedRevision, String reason) implements StrictApiRequest {}

  public record Registration(String text, Boolean publicUseAllowed) implements StrictApiRequest {}

  @GetMapping("/drafts")
  public List<AutonomousDraft> drafts(
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    return drafts.list(limit);
  }

  @GetMapping("/drafts/{id}")
  public AutonomousDraft draft(@PathVariable("id") String id) {
    return drafts.get(id);
  }

  @PostMapping("/drafts/generate")
  public AutonomousGenerationService.Result generate(
      @RequestBody(required = false) GenerationRequest request) {
    var service = generation.get();
    return service == null
        ? new AutonomousGenerationService.Result(
            AutonomousGenerationService.Status.SKIPPED, "disabled", null)
        : service.manual();
  }

  @PostMapping("/drafts/{id}/edit")
  public AutonomousDraft edit(@PathVariable("id") String id, @RequestBody Edit request) {
    var input = required(request);
    return drafts.edit(id, revision(input.expectedRevision()), input.text());
  }

  @PostMapping("/drafts/{id}/regenerate")
  public AutonomousGenerationService.Result regenerate(
      @PathVariable("id") String id, @RequestBody Revision request) {
    int expected = revision(required(request).expectedRevision());
    var service = generation.get();
    return service == null
        ? new AutonomousGenerationService.Result(
            AutonomousGenerationService.Status.SKIPPED, "disabled", id, drafts.get(id))
        : service.regenerate(id, expected);
  }

  @PostMapping("/drafts/{id}/reject")
  public AutonomousDraft reject(@PathVariable("id") String id, @RequestBody Rejection request) {
    var input = required(request);
    return drafts.reject(
        id, revision(input.expectedRevision()), input.reason() == null ? "" : input.reason());
  }

  @PostMapping("/drafts/{id}/publish")
  public AutonomousDraft publish(
      @PathVariable("id") String id, @RequestBody Revision request, Principal principal) {
    int expected = revision(required(request).expectedRevision());
    return publication().publish(id, expected, actor(principal));
  }

  @PostMapping("/drafts/{id}/reconcile")
  public AutonomousDraft reconcile(
      @PathVariable("id") String id, @RequestBody Revision request, Principal principal) {
    int expected = revision(required(request).expectedRevision());
    return publication().reconcile(id, expected, actor(principal));
  }

  private AutonomousPublicationService publication() {
    var service = publication.get();
    if (service == null) throw new SecurityException("Publication is unavailable");
    return service;
  }

  private static String actor(Principal principal) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank())
      throw new SecurityException("Authentication is required");
    return principal.getName();
  }

  @GetMapping("/sources")
  public List<AutonomousPublicSources.Source> sources(
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    return sources.list(limit);
  }

  @GetMapping("/sources/{id}")
  public AutonomousPublicSources.Source source(@PathVariable("id") String id) {
    return sources.get(id);
  }

  @PostMapping("/sources")
  public AutonomousPublicSources.Source register(@RequestBody Registration request) {
    var input = required(request);
    return sources.register(input.text(), Boolean.TRUE.equals(input.publicUseAllowed()), "api");
  }

  @PostMapping("/sources/{id}/edit")
  public AutonomousPublicSources.Source editSource(
      @PathVariable("id") String id, @RequestBody Edit request) {
    var input = required(request);
    return sources.edit(id, revision(input.expectedRevision()), input.text(), "api");
  }

  @PostMapping("/sources/{id}/permit")
  public AutonomousPublicSources.Source permit(
      @PathVariable("id") String id, @RequestBody Revision request) {
    return sources.permission(id, revision(required(request).expectedRevision()), true, "api");
  }

  @PostMapping("/sources/{id}/revoke")
  public AutonomousPublicSources.Source revoke(
      @PathVariable("id") String id, @RequestBody Revision request) {
    return sources.permission(id, revision(required(request).expectedRevision()), false, "api");
  }

  private static int revision(int revision) {
    if (revision < 1) throw new IllegalArgumentException("Review revision is required");
    return revision;
  }

  private static <T> T required(T request) {
    if (request == null) throw new IllegalArgumentException("Request is required");
    return request;
  }

  @ExceptionHandler(AutonomousConflictException.class)
  public ResponseEntity<Map<String, String>> conflict() {
    return ResponseEntity.status(409)
        .body(Map.of("message", "Autonomous draft changed; reload before retrying"));
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<Map<String, String>> missing() {
    return ResponseEntity.status(404)
        .body(Map.of("message", "Autonomous draft or topic not found"));
  }

  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<Map<String, String>> denied() {
    return ResponseEntity.status(403)
        .body(Map.of("message", "Autonomous publication is not allowed"));
  }

  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<Map<String, String>> invalid() {
    return ResponseEntity.badRequest().body(Map.of("message", "Invalid autonomous draft request"));
  }
}
