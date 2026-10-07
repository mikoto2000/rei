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

/** Explicit non-conversational operations over the existing Run lifecycle and shared project queue. */
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
  public BackgroundRunSubmitService(ProjectRegistry projects, RunRegistry registry, RunService runs,
      ConversationInputRouter router, CommandCancellationService cancellation, AgentEventFactory events, AgentEventBus bus,
      WebPageSummarizerService summaries, ImageGenerationService images, ImageProperties properties) {
    this.projects=projects; this.registry=registry; this.runs=runs; this.router=router; this.cancellation=cancellation;
    this.events=events; this.bus=bus; this.summaries=summaries; this.images=images; this.properties=properties;
  }
  public AgentRunContext summary(String projectId, String url) {
    requireText(url, 4096);
    URI source=URI.create(url);
    if (!("https".equalsIgnoreCase(source.getScheme()) || "http".equalsIgnoreCase(source.getScheme()))
        || source.getHost()==null || source.getUserInfo()!=null) throw new IllegalArgumentException("Invalid URL");
    return submit(projectId, context -> summaries.summarize(source).summary());
  }
  public AgentRunContext image(String projectId, String prompt, String size) {
    requireText(prompt, 10000);
    var dimensions=ImageSize.parse(size==null?properties.getSize():size);
    return submit(projectId, context -> {
      // Server-selected destination: HTTP never receives an output path or model configuration.
      var output=context.projectRoot().resolve(".rei/web-images").resolve(context.runId()+".png");
      var result=images.generate(new ImageGenerationRequest(prompt,output,null,dimensions,true));
      if(!result.success()) {
        if("cancelled".equals(result.message())) throw new java.util.concurrent.CancellationException();
        throw new IllegalStateException("Image generation failed");
      }
      return result.artifactId()==null?".rei/web-images/"+context.runId()+".png":"Artifact: "+result.artifactId();
    });
  }
  private static void requireText(String text,int max) {
    if(text==null || text.isBlank() || text.length()>max) throw new IllegalArgumentException("Invalid input");
  }
  private AgentRunContext submit(String projectId, Function<AgentRunContext,String> operation) {
    requireText(projectId,128);
    var project=projects.resolveById(projectId).orElseThrow(()->new ResourceNotFoundException("Project"));
    // Empty internal conversation identity denotes an operation, with no persisted Session or Turn.
    var context=new AgentRunContext(UUID.randomUUID().toString(),"",project.root(),project.id(),AgentRunContext.RequestSource.WEB);
    registry.register(context);
    try { router.submitOperation(context, () -> execute(context,operation), work -> runs.execute(context,work)); }
    catch(RuntimeException | Error error) { registry.forget(context.runId()); throw error; }
    return context;
  }
  private void execute(AgentRunContext context, Function<AgentRunContext,String> operation) {
    try(var scope=AgentRunScope.open(context)) {
      cancellation.begin(Thread.currentThread());
      try {
        if(cancellation.isCancellationRequested()) return;
        bus.publish(events.runStarted(context.runId(),"operation",null).withOwnership(context));
        String result=operation.apply(context);
        if(cancellation.isCancellationRequested() || Thread.currentThread().isInterrupted()) return;
        bus.publish(events.messageDelta(context.runId(),result).withOwnership(context));
        bus.publish(events.runCompleted(context.runId(),0).withOwnership(context));
      } catch(java.util.concurrent.CancellationException error) {
        runs.cancel(context.runId());
      } finally { cancellation.clear(); }
    }
  }
}
