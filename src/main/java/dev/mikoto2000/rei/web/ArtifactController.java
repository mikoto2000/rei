package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.HistoryPage;
import dev.mikoto2000.rei.artifact.Artifact;
import dev.mikoto2000.rei.artifact.ArtifactSourceExport;
import dev.mikoto2000.rei.artifact.ArtifactStore;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.artifacts.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {
  private final ArtifactStore store;

  public ArtifactController(ArtifactStore store) {
    this.store = store;
  }

  private ArtifactSourceExport exports;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setSourceExport(ArtifactSourceExport exports) {
    this.exports = exports;
  }

  public record ExportRequest(
      String projectId, String sessionId, String sourceKind, String sourceId, String version)
      implements StrictApiRequest {}

  @PostMapping("/export")
  public ResponseEntity<Artifact> export(@RequestBody ExportRequest request)
      throws java.io.IOException {
    if (exports == null)
      throw new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Artifact export");
    var item =
        exports.export(
            request.projectId(),
            request.sessionId(),
            request.sourceKind(),
            request.sourceId(),
            request.version());
    return ResponseEntity.created(java.net.URI.create("/api/v1/artifacts/" + item.artifactId()))
        .body(item);
  }

  @GetMapping
  public HistoryPage<Artifact> list(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String sessionId,
      @RequestParam(required = false) String runId,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    return store.list(projectId, sessionId, runId, limit, cursor);
  }

  @GetMapping("/{id}")
  public Artifact get(
      @PathVariable String id,
      @RequestParam String projectId,
      @RequestParam(required = false) String sessionId) {
    return store.get(projectId, sessionId, id);
  }

  @GetMapping("/{id}/content")
  public ResponseEntity<byte[]> content(
      @PathVariable String id,
      @RequestParam String projectId,
      @RequestParam(required = false) String sessionId,
      @RequestHeader(value = "Range", required = false) String range) {
    var item = store.get(projectId, sessionId, id);
    if (range != null)
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    var bytes = store.content(projectId, sessionId, id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(item.mediaType()))
        .contentLength(bytes.length)
        .eTag(item.sha256())
        .cacheControl(CacheControl.noStore())
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename(item.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; sandbox")
        .header("Accept-Ranges", "none")
        .body(bytes);
  }

  public record DeleteRequest(String projectId, String sessionId) implements StrictApiRequest {}

  @DeleteMapping("/{id}")
  public Artifact delete(@PathVariable String id, @RequestBody DeleteRequest request) {
    return store.delete(request.projectId(), request.sessionId(), id);
  }
}
