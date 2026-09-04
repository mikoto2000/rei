package dev.mikoto2000.rei.computer;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public class ComputerUseLoop {
  private final ComputerUseService service;
  private final ComputerUseProperties properties;

  public ComputerUseLoop(ComputerUseService service, ComputerUseProperties properties) {
    this.service = service;
    this.properties = properties;
  }

  public ComputerUseLoopResult run(String goal, Function<ComputerObservation, ComputerActionRequest> policy,
      BooleanSupplier cancellationRequested) {
    Set<String> seenActions = new HashSet<>();
    ComputerObservation observation = service.observe(null);
    for (int i = 0; i < properties.getMaxActions(); i++) {
      if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
        return new ComputerUseLoopResult(false, "cancelled", i, observation);
      }
      ComputerActionRequest action = policy.apply(observation);
      if (action == null) {
        return new ComputerUseLoopResult(true, "complete", i, observation);
      }
      String signature = action.action() + ":" + action.elementId() + ":" + action.text() + ":" + action.keyStroke();
      if (!seenActions.add(signature)) {
        return new ComputerUseLoopResult(false, "repeated action detected", i, observation);
      }
      ComputerActionResult result = service.act(action);
      if (!result.success()) {
        return new ComputerUseLoopResult(false, result.failureReason().orElse("action failed"), i + 1, observation);
      }
      observation = service.observe(null);
    }
    return new ComputerUseLoopResult(false, "maximum action count reached", properties.getMaxActions(), observation);
  }
}
