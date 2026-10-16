package dev.mikoto2000.rei.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class StableToolDefinitionsTest {
  ToolCallback tool(String name, String schema) {
    var callback = mock(ToolCallback.class);
    when(callback.getToolDefinition())
        .thenReturn(
            ToolDefinition.builder().name(name).description("fixed").inputSchema(schema).build());
    return callback;
  }

  @Test
  void sortsOnlySuppliedToolsAndCanonicalizesObjectKeysPreservingArraysAndExecution() {
    var z =
        tool(
            "z",
            "{\"required\":[\"b\",\"a\"],\"properties\":{\"b\":{\"type\":\"string\"},\"a\":{\"type\":\"string\"}},\"type\":\"object\"}");
    var a = tool("a", "{}");
    var options = OpenAiChatOptions.builder().toolCallbacks(z, a).toolChoice("auto").build();
    var original = new Prompt("request", options);
    var stable = StableToolDefinitions.prompt(original);
    var callbacks =
        ((org.springframework.ai.model.tool.ToolCallingChatOptions) stable.getOptions())
            .getToolCallbacks();
    assertEquals(
        List.of("a", "z"), callbacks.stream().map(c -> c.getToolDefinition().name()).toList());
    assertEquals(
        "{\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"string\"}},\"required\":[\"b\",\"a\"],\"type\":\"object\"}",
        callbacks.getLast().getToolDefinition().inputSchema());
    callbacks.getLast().call("input");
    verify(z).call("input");
    assertEquals(List.of(z, a), options.getToolCallbacks());
    assertEquals(stable.getInstructions(), original.getInstructions());
    assertEquals("auto", ((OpenAiChatOptions) stable.getOptions()).getToolChoice());
  }

  @Test
  void preservesExactDecimalSchemaConstraints() {
    var schema = "{\"type\":\"number\",\"minimum\":0.12345678901234567890123456789}";
    assertTrue(
        StableToolDefinitions.callbacks(List.of(tool("a", schema)))
            .getFirst()
            .getToolDefinition()
            .inputSchema()
            .contains("0.12345678901234567890123456789"));
  }

  @Test
  void malformedSchemaSafelyKeepsOriginalAndDoesNotIntroduceTools() {
    var tool = tool("a", "invalid");
    assertEquals(
        "invalid",
        StableToolDefinitions.callbacks(List.of(tool))
            .getFirst()
            .getToolDefinition()
            .inputSchema());
    assertEquals(List.of(), StableToolDefinitions.callbacks(List.of()));
  }
}
