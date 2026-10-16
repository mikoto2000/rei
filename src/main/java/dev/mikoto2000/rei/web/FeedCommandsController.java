package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.feed.FeedOpmlImportService;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.feed.command.FeedCommand;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/feed/commands")
public final class FeedCommandsController {
  private final FeedService feeds;
  private final FeedOpmlImportService imports;
  private final IntegrationRunSubmitService runs;

  public FeedCommandsController(
      FeedService feeds, FeedOpmlImportService imports, IntegrationRunSubmitService runs) {
    this.feeds = feeds;
    this.imports = imports;
    this.runs = runs;
  }

  public record Text(String text) {}

  public record Import(String path) implements StrictApiRequest {}

  public record Add(String url, String displayName) implements StrictApiRequest {}

  public record Edit(String displayName, Boolean enabled) implements StrictApiRequest {}

  public record Update(String projectId, String sessionId, Long feedId)
      implements StrictApiRequest {}

  public record Summary(String projectId, String sessionId, Long feedId)
      implements StrictApiRequest {}

  public record ItemSummary(String projectId, String sessionId, Long itemId)
      implements StrictApiRequest {}

  @GetMapping
  public Text list() {
    return text(FeedCommand.reportList(feeds));
  }

  @PostMapping
  public Text add(@RequestBody Add request) {
    required(request.url());
    optional(request.displayName());
    return text(FeedCommand.reportAdd(feeds, request.url(), request.displayName()));
  }

  @PatchMapping("/{id}")
  public Text edit(@PathVariable long id, @RequestBody Edit request) {
    optional(request.displayName());
    return text(FeedCommand.reportEdit(feeds, id, request.displayName(), request.enabled()));
  }

  @DeleteMapping("/{id}")
  public Text delete(@PathVariable long id) {
    return text(FeedCommand.reportDelete(feeds, id));
  }

  @PostMapping("/imports")
  public Text importOpml(@RequestBody Import request) {
    required(request.path());
    if (!java.nio.file.Path.of(request.path()).isAbsolute())
      throw new IllegalArgumentException("Absolute OPML path required");
    return text(FeedCommand.reportImport(imports, request.path()));
  }

  @GetMapping("/items")
  public Text items(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "10") int limit) {
    return text(
        FeedCommand.reportItems(
            feeds,
            from == null ? null : OffsetDateTime.parse(from),
            to == null ? null : OffsetDateTime.parse(to),
            limit));
  }

  @PostMapping("/update")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> update(
      @RequestBody Update request) {
    required(request.projectId());
    return accepted(runs.updateFeeds(request.projectId(), request.sessionId(), request.feedId()));
  }

  @PostMapping("/summary")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> summary(
      @RequestBody Summary request) {
    required(request.projectId());
    if (request.feedId() != null) throw new IllegalArgumentException("Summary takes no feed ID");
    return accepted(runs.summarizeFeeds(request.projectId(), request.sessionId()));
  }

  @PostMapping("/items/summarize")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> summarizeItem(
      @RequestBody ItemSummary request) {
    required(request.projectId());
    if (request.itemId() == null) throw new IllegalArgumentException("Item required");
    return accepted(
        runs.summarizeFeedItem(request.projectId(), request.sessionId(), request.itemId()));
  }

  private static void optional(String value) {
    if (value != null) required(value);
  }

  private static void required(String value) {
    if (value == null || value.isBlank() || value.length() > 16384 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid feed argument");
  }

  private static Text text(String value) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));
  }

  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(
      dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/" + run.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(run.runId()));
  }
}
