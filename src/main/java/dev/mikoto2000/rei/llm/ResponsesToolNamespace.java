package dev.mikoto2000.rei.llm;

import com.openai.core.JsonValue;
import com.openai.models.responses.NamespaceTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.Tool;
import java.util.List;
import java.util.Set;

/** Typed SDK adaptation for Rei's locally executed functions; never rewrites HTTP JSON. */
final class ResponsesToolNamespace {
  private ResponsesToolNamespace() {}

  static ResponseCreateParams request(ResponseCreateParams request) {
    var tools = request.tools().orElse(List.of());
    if (tools.isEmpty()) return request;
    var functions =
        tools.stream()
            .map(tool -> {
              var function =
                  tool.function()
                      .orElseThrow(
                          () -> new IllegalArgumentException("Plan requires local function tools"));
              if (!Set.of("type", "name", "description", "parameters", "strict")
                  .containsAll(function._allProperties().keySet()))
                throw new IllegalArgumentException("Unsupported Plan function definition");
              return NamespaceTool.Tool.ofFunction(
                  NamespaceTool.Tool.Function.builder()
                      .name(function.name())
                      .description(function._description())
                      .parameters(
                          JsonValue.from(
                              function
                                  .parameters()
                                  .orElseThrow(() ->
                                      new IllegalArgumentException(
                                          "Plan function parameters are required"))
                                  ._allProperties()))
                      .strict(function._strict())
                      .build());
            })
            .toList();
    return request.toBuilder()
        .tools(
            List.of(
                Tool.ofNamespace(
                    NamespaceTool.builder()
                        .name("rei")
                        .description("Locally executed Rei tools")
                        .tools(functions)
                        .build())))
        .build();
  }
}
