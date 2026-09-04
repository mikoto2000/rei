package dev.mikoto2000.rei.computer;

import org.springframework.ai.tool.annotation.Tool;

public class ComputerUseTools {
  private final ComputerUseService service;

  public ComputerUseTools(ComputerUseService service) {
    this.service = service;
  }

  @Tool(name = "computerObserve", description = """
      Observe the current Windows desktop through semantic UI Automation data.
      Use this before any computerAct call. The returned element ids are temporary and valid only for that observation.
      Prefer activeWindowOnly=true and bounded maxDepth/maxElements unless the goal requires broader context.
      """)
  public ComputerObservation computerObserve(ComputerObservationRequest request) {
    return service.observe(request);
  }

  @Tool(name = "computerAct", description = """
      Act on one element from a recent computerObserve result.
      Provide observationId and elementId from the observation. Prefer semantic actions such as INVOKE, SET_VALUE,
      TOGGLE, and FOCUS. Physical fallback is only attempted when the element is enabled, visible, has valid bounds,
      belongs to the observation, and exposes PHYSICAL_CLICK.
      """)
  public ComputerActionResult computerAct(ComputerActionRequest request) {
    return service.act(request);
  }
}
