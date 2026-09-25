package dev.mikoto2000.rei.activity;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DailySummaryWriterTest {
  DailySummaryAggregate aggregate() {return DailySummaryTest.aggregate(List.of(DailySummaryTest.segment(60,1200,"development","rei","GitHub",EntertainmentDisposition.NON_ENTERTAINMENT)));}
  String valid() {return """
    {"overview":"開発が多く観測されました。","timeOfDay":{"lateNight":"開発の表示が主でした。","morning":null,"afternoon":null,"evening":null},
     "workThemes":["rei の開発"],"nonWorkActivities":[],"trend":"開発関連の表示が続く区間がありました。"}
    """;}
  @Test void structuredResponseAndAggregateOnlyPromptWithoutTools() throws Exception {
    var model=mock(ChatModel.class);var options=OpenAiChatOptions.builder().model("model").maxTokens(99).build();
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(valid()))))));
    var writer=new LlmDailySummaryWriter(()->model,()->options,Duration.ofSeconds(1));
    var output=writer.write(aggregate());assertThat(output.workThemes()).containsExactly("rei の開発");
    var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(model).stream(prompt.capture());verify(model,never()).call(any(Prompt.class));
    var sent=(OpenAiChatOptions)prompt.getValue().getOptions();
    assertThat(sent.getMaxCompletionTokens()).isEqualTo(2048);assertThat(sent.getMaxTokens()).isNull();
    assertThat(sent.getInternalToolExecutionEnabled()).isFalse();assertThat(options.getMaxTokens()).isEqualTo(99);
    assertThat(sent.getResponseFormat().getJsonSchema().getName()).isEqualTo("activity_daily_summary");
    assertThat(prompt.getValue().getUserMessage().getText()).doesNotContain("GitHub","screenshot","observations","fineSessionIds","contentTitle");
    assertThat(prompt.getValue().getUserMessage().getText().length()).isLessThan(16000);
  }
  @ParameterizedTest @ValueSource(strings={"null","{}","not-json","[]",
      "{\"overview\":null,\"timeOfDay\":{},\"workThemes\":[],\"nonWorkActivities\":[],\"trend\":\"\"}"})
  void malformedOrNullOutputIsRejected(String json) {
    assertThatThrownBy(()->LlmDailySummaryWriter.parse(json,aggregate())).isInstanceOf(Exception.class);
  }
  @Test void rejectsExcessiveArraysInventedThemesAbsentBucketsAndTrailingOutput() {
    for(var json:List.of(valid().replace("[\"rei の開発\"]","[\"rei の開発\",\"rei の開発\",\"rei の開発\",\"rei の開発\",\"rei の開発\",\"rei の開発\"]"),
        valid().replace("rei の開発","invented deadline"),valid().replace("\"morning\":null","\"morning\":\"開発\""),
        valid().replace("開発が多く観測されました。","x".repeat(401)),valid()+"{}",valid().replace("開発が多く観測されました。","集中していました。")))
      assertThatThrownBy(()->LlmDailySummaryWriter.parse(json,aggregate())).isInstanceOf(Exception.class);
  }
  @Test void timeoutCancelsStreamAndServiceReturnsFallback() {
    var model=mock(ChatModel.class);var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
    when(model.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never().doOnCancel(()->cancelled.set(true)));
    var writer=new LlmDailySummaryWriter(()->model,()->OpenAiChatOptions.builder().build(),Duration.ofMillis(20));
    var service=new DailySummaryService(()->DailySummaryTest.NAMES,writer);
    var output=org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),()->service.summarize(
        DailySummaryTest.DATE,DailySummaryTest.RANGE,ZoneOffset.UTC,.5,List.of(DailySummaryTest.segment(60,60,"development","rei","",EntertainmentDisposition.NON_ENTERTAINMENT))));
    assertThat(cancelled).isTrue();assertThat(output).contains("rei の開発","全体:");
  }
  @Test void malformedCompletionFallsBackInsteadOfPrintingRawResponse() {
    var model=mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE RAW INVALID"))))));
    var service=new DailySummaryService(()->DailySummaryTest.NAMES,new LlmDailySummaryWriter(()->model,()->OpenAiChatOptions.builder().build(),Duration.ofSeconds(1)));
    assertThat(service.summarize(DailySummaryTest.DATE,DailySummaryTest.RANGE,ZoneOffset.UTC,.5,
        List.of(DailySummaryTest.segment(60,60,"development","rei","",EntertainmentDisposition.NON_ENTERTAINMENT)))).contains("rei の開発").doesNotContain("PRIVATE");
  }
}
