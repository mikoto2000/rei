package dev.mikoto2000.rei.core.chat;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import dev.mikoto2000.rei.llm.OutputLimitDetector;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Ephemeral explicit loop: no advisors, persistence, planner, or ambient tool resolution. */
public final class BoundedToolLoop {
  private final ToolLoopSupport tools = new ToolLoopSupport();
  public static final class MaxStepsExceeded extends RuntimeException { }
  public Mono<String> run(ChatModel model, Prompt prompt, int maxSteps, AgentRunContext owner, Runnable checkActive) {
    return iteration(model, prompt, maxSteps, owner, checkActive);
  }
  private Mono<String> iteration(ChatModel model, Prompt prompt, int remaining, AgentRunContext owner, Runnable checkActive) {
    return Mono.defer(() -> {
      checkActive.run();
      if (remaining <= 0) return Mono.error(new MaxStepsExceeded());
      AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
      return new MessageAggregator().aggregate(model.stream(prompt), aggregated::set).then(Mono.defer(() -> {
        checkActive.run();
        var response = aggregated.get();
        if (response == null || response.getResult() == null) return Mono.error(new IllegalStateException("Empty response"));
        if (OutputLimitDetector.isOutputLimitReached(response)) return Mono.error(new IllegalStateException("Output limit"));
        if (!response.hasToolCalls()) return Mono.just(Objects.toString(response.getResult().getOutput().getText(), ""));
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
          if (result.returnDirect()) return Mono.just(ToolExecutionResult.buildGenerations(result).getFirst().getOutput().getText());
          return iteration(model, new Prompt(result.conversationHistory(), prompt.getOptions()), remaining - 1, owner, checkActive);
        });
      }));
    });
  }
}
