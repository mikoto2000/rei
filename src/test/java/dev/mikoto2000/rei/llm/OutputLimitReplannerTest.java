package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import dev.mikoto2000.rei.core.service.ModelHolderService;

class OutputLimitReplannerTest {

  @Test
  void sendsRequiredContextToPlannerLlm() {
    ChatModel chatModel = Mockito.mock(ChatModel.class);
    LlmModelProvider modelProvider = Mockito.mock(LlmModelProvider.class);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    LlmProperties properties = new LlmProperties();
    properties.setMaxOutputTokens(2048);
    when(modelHolderService.get()).thenReturn("default-model");
    when(modelProvider.chatModel(LlmFeature.OUTPUT_LIMIT_PLANNER)).thenReturn(chatModel);
    when(modelProvider.chatOptions(LlmFeature.OUTPUT_LIMIT_PLANNER, "default-model"))
        .thenReturn(OpenAiChatOptions.builder().model("default-model").maxTokens(2048).build());
    when(chatModel.call(any(Prompt.class))).thenReturn(response("""
        {"subgoals":[{"id":"one","goal":"小さく実行する"}],"finalGoal":"統合する"}
        """));
    OutputLimitReplanner replanner = new OutputLimitReplanner(modelProvider, modelHolderService, properties,
        new OutputLimitReplanParser());

    OutputLimitReplanPlan plan = replanner.replan(new OutputLimitReplanRequest(
        "元のユーザー要求",
        "現在のゴール",
        "ここまでの成果",
        "途中まで生成された内容",
        1,
        2,
        29));

    assertThat(plan.subgoals()).hasSize(1);
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    String prompt = promptCaptor.getValue().getContents();
    assertThat(prompt).contains("元のユーザー要求");
    assertThat(prompt).contains("現在のゴール");
    assertThat(prompt).contains("ここまでの成果");
    assertThat(prompt).contains("途中まで生成された内容");
    assertThat(prompt).contains("finish_reason == length");
    assertThat(prompt).contains("2048");
    assertThat(prompt).contains("29");
    assertThat(prompt).contains("8");
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
  @Test void plannerReportsUsageBeforeParsingResult() {
    var model=Mockito.mock(ChatModel.class);var provider=Mockito.mock(LlmModelProvider.class);
    var holder=Mockito.mock(ModelHolderService.class);when(holder.get()).thenReturn("model");
    when(provider.chatModel(LlmFeature.OUTPUT_LIMIT_PLANNER)).thenReturn(model);
    when(provider.chatOptions(LlmFeature.OUTPUT_LIMIT_PLANNER,"model")).thenReturn(OpenAiChatOptions.builder().model("model").build());
    when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("not valid plan"))),
        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().usage(new org.springframework.ai.chat.metadata.DefaultUsage(2,3)).build()));
    var tokens=new java.util.concurrent.atomic.AtomicInteger();
    org.assertj.core.api.Assertions.assertThatThrownBy(()->new OutputLimitReplanner(provider,holder,new LlmProperties()).replan(
        new OutputLimitReplanRequest("task","goal","progress","partial",1,2,3),tokens::set)).isInstanceOf(RuntimeException.class);
    assertThat(tokens).hasValue(5);
  }
}
