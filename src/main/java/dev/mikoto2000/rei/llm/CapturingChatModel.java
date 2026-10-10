package dev.mikoto2000.rei.llm;

import java.util.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import dev.mikoto2000.rei.llm.capture.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import reactor.core.publisher.Flux;

/** Each opted-in logical call owns its SDK transport and immutable HTTP attribution. */
final class CapturingChatModel implements ChatModel {
  private final OpenAiChatModel delegate;
  private final CaptureStore store;
  private final List<OpenAiHttpClientBuilderCustomizer> customizers;
  private final io.micrometer.observation.ObservationRegistry observations;
  private final io.micrometer.core.instrument.MeterRegistry meters;
  CapturingChatModel(OpenAiChatModel delegate,CaptureStore store,List<OpenAiHttpClientBuilderCustomizer> customizers,
      io.micrometer.observation.ObservationRegistry observations,io.micrometer.core.instrument.MeterRegistry meters){
    this.delegate=delegate;this.store=store;this.customizers=List.copyOf(customizers);this.observations=observations;this.meters=meters;
  }
  public ChatOptions getOptions(){return delegate.getOptions();}
  private CaptureStore.LogicalCall logical(Prompt prompt){
    if(!(prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options)||options.getToolContext()==null)return null;
    var context=options.getToolContext();AgentRunContext owner=null;
    if(context.get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY) instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution)owner=execution.runContext();
    if(owner==null&&context.get(AgentRunContext.class.getName()) instanceof AgentRunContext run)owner=run;
    // Store contains only Root Runs atomically admitted by the keyboard submission boundary.
    return owner!=null&&owner.requestSource()==AgentRunContext.RequestSource.SHELL?store.logicalCall(owner.runId(),null):null;
  }
  private record Owned(OpenAiChatModel model,com.openai.client.OpenAIClient client) implements AutoCloseable {
    public void close(){try{client.close();}catch(RuntimeException ignored){}}
  }
  private Owned create(CaptureStore.LogicalCall logical){
    var o=delegate.getOptions();var configured=new ArrayList<>(customizers);
    configured.add(builder->builder.interceptor(new CaptureInterceptor(store,logical)));
    var client=OpenAiSetup.setupSyncClient(o.getBaseUrl(),o.getApiKey(),o.getCredential(),o.getMicrosoftDeploymentName(),
        o.getMicrosoftFoundryServiceVersion(),o.getOrganizationId(),o.isMicrosoftFoundry(),o.isGitHubModels(),o.getModel(),o.getTimeout(),
        o.getMaxRetries(),o.getProxy(),o.getCustomHeaders(),observations,meters,configured);
    try{
      var builder=OpenAiChatModel.builder().options(o).openAiClient(client).openAiClientAsync(client.async()).observationRegistry(observations);
      // RunAwareToolCallingAdvisor or Rei's run loop owns tool execution, just as for the delegate.
      return new Owned(builder.build(),client);
    }catch(RuntimeException error){client.close();throw error;}
  }
  public ChatResponse call(Prompt prompt){
    var logical=logical(prompt);if(logical==null)return delegate.call(prompt);
    Owned owned;try{owned=create(logical);}catch(RuntimeException failure){store.missed(logical);return delegate.call(prompt);}
    try(owned){return owned.model.call(prompt);}
  }
  public Flux<ChatResponse> stream(Prompt prompt){
    return Flux.defer(()->{
      var logical=logical(prompt);if(logical==null)return delegate.stream(prompt);
      Owned owned;try{owned=create(logical);}catch(RuntimeException failure){store.missed(logical);return delegate.stream(prompt);}
      try{return owned.model.stream(prompt).doFinally(signal->{if(signal==reactor.core.publisher.SignalType.CANCEL)store.cancel(logical);owned.close();});}
      catch(RuntimeException error){owned.close();throw error;}
    });
  }
}
