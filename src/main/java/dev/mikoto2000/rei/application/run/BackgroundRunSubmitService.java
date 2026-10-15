package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.image.*;
import dev.mikoto2000.rei.summarize.WebPageSummarizerService;
import java.net.URI;
import java.util.UUID;
import java.util.function.Function;

/**
 * Explicit non-conversational operations over the existing Run lifecycle and shared project queue.
 */
public final class BackgroundRunSubmitService {
  private final ProjectRegistry projects;
  private final RunRegistry registry;
  private final RunService runs;
  private final ConversationInputRouter router;
  private final CommandCancellationService cancellation;
  private final AgentEventFactory events;
  private final AgentEventBus bus;
  private final WebPageSummarizerService summaries;
  private final ImageGenerationService images;
  private final ImageProperties properties;
  private dev.mikoto2000.rei.core.project.ProjectRunStateStore states;
  private dev.mikoto2000.rei.core.execution.BackgroundCommands background;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setShellState(
      dev.mikoto2000.rei.core.project.ProjectRunStateStore states,
      dev.mikoto2000.rei.core.execution.BackgroundCommands background) {
    this.states = states;
    this.background = background;
  }

  private final java.util.concurrent.ConcurrentMap<String, String> activeSummaries =
      new java.util.concurrent.ConcurrentHashMap<>();

  public AgentRunContext summaryForCli(String projectId, String url) {
    requireText(url, 4096);
    var source = URI.create(url);
    if (!("https".equalsIgnoreCase(source.getScheme())
            || "http".equalsIgnoreCase(source.getScheme()))
        || source.getHost() == null
        || source.getUserInfo() != null) throw new IllegalArgumentException("Invalid URL");
    return submit(
        projectId,
        context -> {
          activeSummaries.put(context.runId(), url);
          try {
            String result = summaries.summarize(source).summary();
            save(context, dev.mikoto2000.rei.core.execution.ExecutionType.SUMMARIZE, url, result);
            return result;
          } finally {
            activeSummaries.remove(context.runId());
          }
        });
  }

  public String latestSummaryForCli(String projectId) {
    var project =
        projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    if (background == null) throw new IllegalStateException("Background runtime unavailable");
    var output = new java.util.ArrayList<String>();
    var active =
        activeSummaries.entrySet().stream()
            .map(
                item -> {
                  var run = registry.get(item.getKey());
                  return new dev.mikoto2000.rei.core.execution.ActiveExecution(
                      item.getKey(),
                      projectId,
                      "",
                      project.root(),
                      dev.mikoto2000.rei.core.execution.ExecutionType.SUMMARIZE,
                      item.getValue(),
                      run.startedAt());
                })
            .filter(item -> registry.get(item.id()).context().projectId().equals(projectId))
            .toList();
    background.showLastSummary(project, output::add, false, active);
    return String.join(System.lineSeparator(), output);
  }

  public AgentRunContext imageForCli(
      String projectId, String prompt, String output, String model, String size, boolean raw) {
    requireText(prompt, 10000);
    if (output != null) {
      requireText(output, 16384);
      if (!java.nio.file.Path.of(output).isAbsolute())
        throw new IllegalArgumentException("Absolute image output required");
    }
    if (model != null) requireText(model, 512);
    var dimensions = ImageSize.parse(size == null ? properties.getSize() : size);
    return submit(
        projectId,
        context -> {
          var execution =
              new dev.mikoto2000.rei.core.execution.ActiveExecution(
                  context.runId(),
                  context.projectId(),
                  "",
                  context.projectRoot(),
                  dev.mikoto2000.rei.core.execution.ExecutionType.IMAGE,
                  prompt,
                  registry.get(context.runId()).startedAt());
          try (var scope = dev.mikoto2000.rei.core.execution.ExecutionScope.open(execution)) {
            var result =
                images.generate(
                    new ImageGenerationRequest(
                        prompt,
                        output == null ? null : java.nio.file.Path.of(output),
                        model,
                        dimensions,
                        !raw));
            if (!result.success()) {
              if ("cancelled".equals(result.message()))
                throw new java.util.concurrent.CancellationException();
              bus.publish(
                  events
                      .messageDelta(
                          context.runId(),
                          dev.mikoto2000.rei.event.CredentialRedactor.redact(
                              "[error] 画像生成に失敗しました: " + result.message()))
                      .withOwnership(context));
              throw new IllegalStateException("Image generation failed");
            }
            String report = "画像生成プロンプト: " + result.prompt() + "\n画像を保存しました: " + result.savedPath();
            save(context, dev.mikoto2000.rei.core.execution.ExecutionType.IMAGE, prompt, report);
            return report;
          }
        });
  }

  private void save(
      AgentRunContext context,
      dev.mikoto2000.rei.core.execution.ExecutionType type,
      String source,
      String result) {
    if (cancellation.isCancellationRequested()
        || Thread.currentThread().isInterrupted()
        || registry.get(context.runId()).status() == RunStatus.CANCELLED)
      throw new java.util.concurrent.CancellationException();
    if (states != null)
      states.saveCompleted(
          new dev.mikoto2000.rei.core.execution.CompletedExecution(
              context.runId(),
              context.projectId(),
              "",
              type,
              source,
              result,
              registry.get(context.runId()).startedAt(),
              java.time.Instant.now()));
  }

  public BackgroundRunSubmitService(
      ProjectRegistry projects,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      CommandCancellationService cancellation,
      AgentEventFactory events,
      AgentEventBus bus,
      WebPageSummarizerService summaries,
      ImageGenerationService images,
      ImageProperties properties) {
    this.projects = projects;
    this.registry = registry;
    this.runs = runs;
    this.router = router;
    this.cancellation = cancellation;
    this.events = events;
    this.bus = bus;
    this.summaries = summaries;
    this.images = images;
    this.properties = properties;
  }

  public AgentRunContext summary(String projectId, String url) {
    requireText(url, 4096);
    URI source = URI.create(url);
    if (!("https".equalsIgnoreCase(source.getScheme())
            || "http".equalsIgnoreCase(source.getScheme()))
        || source.getHost() == null
        || source.getUserInfo() != null) throw new IllegalArgumentException("Invalid URL");
    return submit(projectId, context -> summaries.summarize(source).summary());
  }

  public AgentRunContext image(String projectId, String prompt, String size) {
    requireText(prompt, 10000);
    var dimensions = ImageSize.parse(size == null ? properties.getSize() : size);
    return submit(
        projectId,
        context -> {
          // Server-selected destination: HTTP never receives an output path or model configuration.
          var output =
              context.projectRoot().resolve(".rei/web-images").resolve(context.runId() + ".png");
          var result =
              images.generate(new ImageGenerationRequest(prompt, output, null, dimensions, true));
          if (!result.success()) {
            if ("cancelled".equals(result.message()))
              throw new java.util.concurrent.CancellationException();
            throw new IllegalStateException("Image generation failed");
          }
          return result.artifactId() == null
              ? ".rei/web-images/" + context.runId() + ".png"
              : "Artifact: " + result.artifactId();
        });
  }

  private static void requireText(String text, int max) {
    if (text == null || text.isBlank() || text.length() > max)
      throw new IllegalArgumentException("Invalid input");
  }

  private AgentRunContext submit(String projectId, Function<AgentRunContext, String> operation) {
    requireText(projectId, 128);
    var project =
        projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    // Empty internal conversation identity denotes an operation, with no persisted Session or Turn.
    var context =
        new AgentRunContext(
            UUID.randomUUID().toString(),
            "",
            project.root(),
            project.id(),
            AgentRunContext.RequestSource.WEB);
    registry.register(context);
    try {
      router.submitOperation(
          context, () -> execute(context, operation), work -> runs.execute(context, work));
    } catch (RuntimeException | Error error) {
      registry.forget(context.runId());
      throw error;
    }
    return context;
  }

  private void execute(AgentRunContext context, Function<AgentRunContext, String> operation) {
    try (var scope = AgentRunScope.open(context)) {
      cancellation.begin(Thread.currentThread());
      try {
        if (cancellation.isCancellationRequested()) return;
        bus.publish(events.runStarted(context.runId(), "operation", null).withOwnership(context));
        String result = operation.apply(context);
        if (cancellation.isCancellationRequested() || Thread.currentThread().isInterrupted())
          return;
        bus.publish(events.messageDelta(context.runId(), result).withOwnership(context));
        bus.publish(events.runCompleted(context.runId(), 0).withOwnership(context));
      } catch (java.util.concurrent.CancellationException error) {
        runs.cancel(context.runId());
      } finally {
        cancellation.clear();
      }
    }
  }
}
