package dev.mikoto2000.rei.llm.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/** A fresh immutable definition snapshot for every physical model request. */
public final class ToolRequestAssembler {
  private ToolRequestAssembler() {}

  public static Prompt assemble(
      Prompt prompt,
      ToolCatalog catalog,
      RunToolAvailability state,
      ToolCallback search,
      Function<String, Boolean> visible,
      int maximum) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options))
      throw new IllegalArgumentException("Explicit tool options required");
    var callbacks = new ArrayList<ToolCallback>();
    for (String name : state.names().stream().sorted().toList()) {
      if (!visible.apply(name)) continue;
      var cb = name.equals("searchTools") ? search : catalog.callback(name);
      callbacks.add(ToolCatalog.frozen(cb, cb.getToolDefinition()));
    }
    var result =
        new Prompt(
            prompt.getInstructions(),
            options.mutate().toolCallbacks(List.copyOf(callbacks)).build());
    ToolRequestValidator.validate(result, maximum);
    return result;
  }
}
