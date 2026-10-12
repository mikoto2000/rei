package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.dot.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/dot-requests")
public final class DotController {
  private final DotDelegationService service;
  private final DotResumeService resumes;

  public DotController(DotDelegationService service, DotResumeService resumes) {
    this.service = service;
    this.resumes = resumes;
  }

  public record CreateRequest(
      String request_id,
      String session_id,
      String category,
      String instruction,
      String context_summary,
      String expected_output,
      List<String> constraints,
      List<String> acceptance_criteria,
      Object timeout)
      implements StrictApiRequest {
    Map<String, Object> payload() {
      var a = new LinkedHashMap<String, Object>();
      if (request_id != null) a.put("request_id", request_id);
      if (category != null) a.put("category", category);
      a.put("instruction", instruction);
      if (context_summary != null) a.put("context_summary", context_summary);
      if (expected_output != null) a.put("expected_output", expected_output);
      if (constraints != null) a.put("constraints", constraints);
      if (acceptance_criteria != null) a.put("acceptance_criteria", acceptance_criteria);
      if (timeout != null) a.put("timeout", timeout);
      return a;
    }
  }

  @PostMapping
  public Map<String, Object> create(
      @PathVariable String projectId, @RequestBody CreateRequest request) {
    return service.createHuman(projectId, request.session_id(), request.payload());
  }

  @GetMapping
  public List<Map<String, Object>> list(@PathVariable String projectId) {
    return service.list(projectId);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable String projectId, @PathVariable String id) {
    return resumes.status(projectId, id);
  }

  @GetMapping("/{id}/result")
  public Map<String, Object> result(@PathVariable String projectId, @PathVariable String id) {
    return service.result(projectId, id);
  }

  public record ReviewRequest(Boolean confirmed, String status, String note, Object satisfied)
      implements StrictApiRequest {
    Map<String, Object> payload() {
      var out = new LinkedHashMap<String, Object>();
      out.put("confirmed", confirmed);
      out.put("status", status);
      out.put("note", note);
      if (satisfied != null) out.put("satisfied", satisfied);
      return out;
    }
  }

  public record ResumeRequest(Boolean confirmed) implements StrictApiRequest {}

  @PostMapping("/{id}/verify")
  public Map<String, Object> verify(
      @PathVariable String projectId, @PathVariable String id, @RequestBody ReviewRequest request) {
    return resumes.verify(projectId, id, request.payload());
  }

  @PostMapping("/{id}/resume")
  public Map<String, Object> resume(
      @PathVariable String projectId, @PathVariable String id, @RequestBody ResumeRequest request) {
    return resumes.resume(
        projectId, id, Collections.singletonMap("confirmed", request.confirmed()));
  }

  @PostMapping("/{id}/cancel")
  public Map<String, Object> cancel(@PathVariable String projectId, @PathVariable String id) {
    return service.cancel(projectId, id);
  }
}
