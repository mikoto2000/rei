package dev.mikoto2000.rei.core.chat;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import dev.mikoto2000.rei.llm.OutputLimitDetector;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Ephemeral explicit loop: no advisors, persistence, planner, or ambient tool resolution. */
public final class BoundedToolLoop {
  private final ToolLoopSupport tools = new ToolLoopSupport();
  public static final class SharedBudgetExceeded extends RuntimeException { }
  public static final class MaxStepsExceeded extends RuntimeException { }
  public record Outcome(String output, List<Message> history) {
    public Outcome { history = List.copyOf(history); }
  }
  public Mono<String> run(ChatModel model, Prompt prompt, int maxSteps, AgentRunContext owner, Runnable checkActive) {
    return Mono.defer(() -> runWithHistory(model, prompt, new AtomicInteger(maxSteps), owner, checkActive)).map(Outcome::output);
  }
  public Mono<Outcome> runWithHistory(ChatModel model, Prompt prompt, AtomicInteger remaining, AgentRunContext owner, Runnable checkActive) {
    return runWithHistory(model,prompt,remaining,owner,checkActive,null);
  }
  public Mono<Outcome> runWithHistory(ChatModel model,Prompt prompt,AtomicInteger remaining,AgentRunContext owner,Runnable checkActive,
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation) {
    return iteration(model,prompt,remaining,owner,checkActive,reservation);
  }
  private Mono<Outcome> iteration(ChatModel model, Prompt prompt, AtomicInteger remaining, AgentRunContext owner, Runnable checkActive,dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation) {
    return Mono.defer(() -> {
      checkActive.run();
      if (remaining.getAndDecrement() <= 0) return Mono.error(new MaxStepsExceeded());
      if(reservation!=null&&!reservation.tryReserve())return Mono.error(new SharedBudgetExceeded());
      checkActive.run();
      AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
      var started=new java.util.concurrent.atomic.AtomicBoolean();
      var reported=new java.util.concurrent.atomic.AtomicBoolean();
      return new MessageAggregator().aggregate(reactor.core.publisher.Flux.defer(()->{
        started.set(true);return model.stream(prompt);
      }), aggregated::set).then(Mono.defer(() -> {
        checkActive.run();
        var response = aggregated.get();
        if (response == null || response.getResult() == null) return Mono.error(new IllegalStateException("Empty response"));
        if(reservation!=null) {
          reported.set(true);
          var usage=response.getMetadata().getUsage();
          reservation.recordTotalTokens(usage==null?null:usage.getTotalTokens());
        }
        if (OutputLimitDetector.isOutputLimitReached(response)) return Mono.error(new IllegalStateException("Output limit"));
        if (!response.hasToolCalls()) {
          var history = new ArrayList<Message>(prompt.getInstructions());
          history.add(response.getResult().getOutput());
          return Mono.just(new Outcome(Objects.toString(response.getResult().getOutput().getText(), ""), history));
        }
        var options = (ToolCallingChatOptions) prompt.getOptions();
        Set<String> allowed = new HashSet<>();
        options.getToolCallbacks().forEach(tool -> allowed.add(tool.getToolDefinition().name()));
        // Validate the entire batch before any side effect. Never let a resolver find ambient tools.
        for (var generation : response.getResults()) for (var call : generation.getOutput().getToolCalls()) {
          if (!allowed.contains(call.name())) return Mono.error(new IllegalArgumentException("Unrequested tool"));
        }
        return Mono.fromCallable(() -> {
          try (var scope = AgentRunScope.open(owner)) {
            checkActive.run();
            return tools.execute(prompt, response);
          }
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(result -> {
          checkActive.run();
          if (result.returnDirect()) {
            var output = ToolExecutionResult.buildGenerations(result).getFirst().getOutput();
            var history = new ArrayList<Message>(result.conversationHistory()); history.add(output);
            return Mono.just(new Outcome(output.getText(), history));
          }
          return iteration(model, new Prompt(result.conversationHistory(), prompt.getOptions()), remaining, owner, checkActive,reservation);
        });
      })).doOnError(error->{
        if(reservation!=null&&reservation.tokenLimitEnabled()&&started.get()&&!reported.getAndSet(true))reservation.recordTotalTokens(null);
      }).doFinally(signal->{
        if(signal==reactor.core.publisher.SignalType.CANCEL&&reservation!=null&&reservation.tokenLimitEnabled()&&started.get()&&!reported.getAndSet(true)) {
          try{reservation.recordTotalTokens(null);}catch(RuntimeException stopped){/* The accounting state already prevents further calls. */}
        }
      });
    });
  }
}
