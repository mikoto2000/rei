package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

class SpringAiComputerVisionModelTest {
  static ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
  static ComputerObservation observation() {
    return new ComputerObservation("Enter abc", ComputerUseServiceTest.screen(), List.of("Clicked editor"), 2, 20);
  }
  @Test void sendsImageGoalHistoryAndStrictSchemaWithoutTools() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(response(ActionValidationTest.json("DONE", "reason", "\"Text visible\"")));
    var vision = new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, () -> false, 1);
    assertInstanceOf(ComputerAction.Done.class, vision.decide(observation()));
    var prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(prompt.capture());
    var user = (UserMessage) prompt.getValue().getInstructions().getLast();
    assertEquals(1,user.getMedia().size());
    assertTrue(user.getText().contains("Enter abc"));
    assertTrue(user.getText().contains("Clicked editor"));
    assertTrue(user.getText().contains("2/20"));
    var options = (OpenAiChatOptions)prompt.getValue().getOptions();
    assertEquals(ResponseFormat.Type.JSON_SCHEMA,options.getResponseFormat().getType());
    assertEquals(Boolean.TRUE,options.getResponseFormat().getJsonSchema().getStrict());
    assertEquals(Boolean.FALSE,options.getInternalToolExecutionEnabled());
    assertNull(options.getToolChoice());
    assertNull(options.getTools());
    assertTrue(options.getToolCallbacks().isEmpty());
    assertTrue(options.getToolNames().isEmpty());
  }
  @Test void invalidOutputRetriesWithinBudgetAndThenFails() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(response("{}"),response(ActionValidationTest.json("DONE","reason","\"Visible\"")));
    var vision = new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, () -> false, 1);
    assertInstanceOf(ComputerAction.Done.class,vision.decide(observation()));
    verify(model,times(2)).call(any(Prompt.class));
    reset(model);
    when(model.call(any(Prompt.class))).thenReturn(response("{}"));
    assertThrows(IllegalArgumentException.class, () -> vision.decide(observation()));
    verify(model,times(2)).call(any(Prompt.class));
  }
  @Test void cancellationBetweenRepairsPreventsAnotherRequest() {
    var model = mock(ChatModel.class);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    when(model.call(any(Prompt.class))).thenAnswer(call -> { cancelled.set(true); return response("{}"); });
    var vision = new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, cancelled::get, 2);
    assertThrows(java.util.concurrent.CancellationException.class, () -> vision.decide(observation()));
    verify(model).call(any(Prompt.class));
  }
  @Test void refusesAmbientToolsBeforeInference() {
    var model = mock(ChatModel.class);
    when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().toolNames("shell").build());
    assertThrows(IllegalArgumentException.class, () -> new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, () -> false, 1));
    verify(model,never()).call(any(Prompt.class));
  }
  @Test void refusesRawDefaultToolsThatWouldOtherwiseBeMergedIntoNoToolsRequest() {
    var model = mock(ChatModel.class);
    when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().tools(List.of(
        mock(org.springframework.ai.openai.api.OpenAiApi.FunctionTool.class))).build());
    assertThrows(IllegalArgumentException.class, () -> new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, () -> false, 1));
    verify(model,never()).call(any(Prompt.class));
  }
}
