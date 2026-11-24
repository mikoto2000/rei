package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.ModelHolderService;
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

/** 出力上限Replannerの結果と状態の契約を検証するテスト。 */
class OutputLimitReplannerTest {

  @Test
  void nativePlannerUsesItsOwnOptionsWithoutConversationReplayAuthority() {
    var model=Mockito.mock(ChatModel.class);
    var provider=Mockito.mock(LlmModelProvider.class);
    var holder=Mockito.mock(ModelHolderService.class);
    when(holder.get()).thenReturn("main");
    var base=org.springframework.ai.openai.responses.OpenAiResponsesChatOptions.builder()
        .model("native-planner").maxOutputTokens(73).build();
    when(provider.requestOptions(LlmFeature.OUTPUT_LIMIT_PLANNER,"main",false)).thenReturn(base);
    when(provider.chatModel(LlmFeature.OUTPUT_LIMIT_PLANNER)).thenReturn(model);
    var owners=new java.util.ArrayList<Object>();
    when(model.call(any(Prompt.class))).thenAnswer(invocation->{
      Prompt prompt=invocation.getArgument(0);
      assertThat(prompt.getOptions()).isInstanceOf(org.springframework.ai.openai.responses.OpenAiResponsesChatOptions.class);
      var sent=(org.springframework.ai.openai.responses.OpenAiResponsesChatOptions)prompt.getOptions();
      assertThat(sent.getMaxOutputTokens()).isEqualTo(73);
      assertThat(sent.getToolCallbacks()).isEmpty();
      owners.add(sent.getToolContext().get("rei.llm.replay-owner"));
      assertThat(owners.getLast()).asString().startsWith("feature:");
      return response("{\"subgoals\":[{\"id\":\"one\",\"goal\":\"part\"}],\"finalGoal\":\"final\"}");
    });
    var planner=new OutputLimitReplanner(provider,holder,new LlmProperties());
    for(int attempt=0;attempt<2;attempt++) assertThat(planner.replan(new OutputLimitReplanRequest("task","goal","progress","partial",1,2,3)).subgoals()).hasSize(1);
    assertThat(owners).hasSize(2).doesNotHaveDuplicates();
    assertThat(base.getToolContext()).isNull();
  }

  @Test
  void aPlannerWithoutAUsageCallbackStillRejectsAmbientDefaultTools() {
    var model=Mockito.mock(ChatModel.class);
    var provider=Mockito.mock(LlmModelProvider.class);
    var holder=Mockito.mock(ModelHolderService.class);
    when(holder.get()).thenReturn("main");
    when(provider.requestOptions(LlmFeature.OUTPUT_LIMIT_PLANNER,"main",false))
        .thenReturn(OpenAiChatOptions.builder().model("planner").build());
    when(provider.chatOptions(LlmFeature.OUTPUT_LIMIT_PLANNER,"main"))
        .thenReturn(OpenAiChatOptions.builder().model("planner").build());
    when(provider.chatModel(LlmFeature.OUTPUT_LIMIT_PLANNER)).thenReturn(model);
    var callback=Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    when(callback.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder()
        .name("ambient").description("fixture").inputSchema("{\"type\":\"object\"}").build());
    when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().toolCallbacks(List.of(callback)).build());
    when(model.call(any(Prompt.class))).thenReturn(response("{\"subgoals\":[{\"id\":\"one\",\"goal\":\"part\"}],\"finalGoal\":\"final\"}"));
    org.assertj.core.api.Assertions.assertThatThrownBy(()->new OutputLimitReplanner(provider,holder,new LlmProperties())
        .replan(new OutputLimitReplanRequest("task","goal","progress","partial",1,2,3)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("default tools");
    Mockito.verify(model,Mockito.never()).call(any(Prompt.class));
  }

  /** 必須文脈を送信することを確認する。 */
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
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            response(
                """
                {"subgoals":[{"id":"one","goal":"小さく実行する"}],"finalGoal":"統合する"}
                """));
    OutputLimitReplanner replanner =
        new OutputLimitReplanner(
            modelProvider, modelHolderService, properties, new OutputLimitReplanParser());

    OutputLimitReplanPlan plan =
        replanner.replan(
            new OutputLimitReplanRequest("元のユーザー要求", "現在のゴール", "ここまでの成果", "途中まで生成された内容", 1, 2, 29));

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

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  /** 計画の作成について、使用状況を伝えることを確認する。 */
  @Test
  void plannerReportsUsageBeforeParsingResult() {
    var model = Mockito.mock(ChatModel.class);
    var provider = Mockito.mock(LlmModelProvider.class);
    var holder = Mockito.mock(ModelHolderService.class);
    when(holder.get()).thenReturn("model");
    when(provider.chatModel(LlmFeature.OUTPUT_LIMIT_PLANNER)).thenReturn(model);
    when(provider.chatOptions(LlmFeature.OUTPUT_LIMIT_PLANNER, "model"))
        .thenReturn(OpenAiChatOptions.builder().model("model").build());
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("not valid plan"))),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                    .usage(new org.springframework.ai.chat.metadata.DefaultUsage(2, 3))
                    .build()));
    var tokens = new java.util.concurrent.atomic.AtomicInteger();
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new OutputLimitReplanner(provider, holder, new LlmProperties())
                .replan(
                    new OutputLimitReplanRequest("task", "goal", "progress", "partial", 1, 2, 3),
                    tokens::set))
        .isInstanceOf(RuntimeException.class);
    assertThat(tokens).hasValue(5);
  }

  @Test
  void fatalModelFailureRecordsUnknownUsageAndPreservesPrimaryFailure() {
    var model = Mockito.mock(ChatModel.class);
    var provider = Mockito.mock(LlmModelProvider.class);
    var holder = Mockito.mock(ModelHolderService.class);
    when(holder.get()).thenReturn("model");
    when(provider.chatModel(LlmFeature.OUTPUT_LIMIT_PLANNER)).thenReturn(model);
    when(provider.chatOptions(LlmFeature.OUTPUT_LIMIT_PLANNER, "model"))
        .thenReturn(OpenAiChatOptions.builder().model("model").build());
    var fatal = new AssertionError("model failed");
    var accounting = new IllegalStateException("usage unknown");
    when(model.call(any(Prompt.class))).thenThrow(fatal);
    java.util.function.Consumer<Integer> usage = Mockito.mock();
    Mockito.doThrow(accounting).when(usage).accept(null);

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new OutputLimitReplanner(provider, holder, new LlmProperties())
                .replan(
                    new OutputLimitReplanRequest("task", "goal", "progress", "partial", 1, 2, 3),
                    usage))
        .isSameAs(fatal);
    verify(usage).accept(null);
    assertThat(fatal.getSuppressed()).containsExactly(accounting);
  }
}
