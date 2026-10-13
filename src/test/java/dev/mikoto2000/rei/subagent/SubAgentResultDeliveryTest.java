package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.contextbudget.*;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
import dev.mikoto2000.rei.llm.StableToolDefinitions;

@Tag("integration")
class SubAgentResultDeliveryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({"delegateTask,false","delegateTask,true","delegateTasks,false","delegateTasks,true"})
  void singleAndBatchResultsKeepRegisteredPolicyAcrossLazyEventAndStableCallbacks(String name,boolean lazy) throws Exception {
    var runner=mock(SubAgentRunner.class);
    var parallel=mock(ParallelSubAgentDelegator.class);
    var registry=new SubAgentRegistry(directory,new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()),model->true));
    var tools=new SubAgentTools(runner,registry);tools.parallelDelegator(parallel);
    var parser=new SubAgentResultParser();
    String childOutput="{\"status\":\"SUCCESS\",\"summary\":\"review\",\"result\":{\"findings\":[\"first\",\""+
        "日本語🙂\\\"".repeat(3000)+"\",\"last\"]},\"warnings\":[]}";
    var child=new SubAgentResult("reviewer","child-run",SubAgentResult.Status.COMPLETED,childOutput,Instant.EPOCH,Instant.EPOCH,
        SubAgentOutput.fromValidated(parser.parse(childOutput)),List.of());
    String arguments;
    if(name.equals("delegateTask")) {
      when(runner.run("reviewer","review",null)).thenReturn(child);
      arguments="{\"agent\":\"reviewer\",\"task\":\"review\"}";
    } else {
      var requests=List.of(new ParallelSubAgentDelegator.Request("first","reviewer","review",null),
          new ParallelSubAgentDelegator.Request("second","reviewer","followup",null));
      when(parallel.delegate(requests)).thenReturn(new ParallelSubAgentDelegator.Batch(ParallelSubAgentDelegator.Status.COMPLETED,
          List.of(new ParallelSubAgentDelegator.Item("first","reviewer",ParallelSubAgentDelegator.Status.COMPLETED,child),
              new ParallelSubAgentDelegator.Item("second","reviewer",ParallelSubAgentDelegator.Status.COMPLETED,child))));
      arguments="{\"requests\":[{\"id\":\"first\",\"agent\":\"reviewer\",\"task\":\"review\"},{\"id\":\"second\",\"agent\":\"reviewer\",\"task\":\"followup\"}]}";
    }
    ToolCallback callback;
    if(lazy) {
      var factory=new DefaultListableBeanFactory();factory.registerSingleton("subagent-tools",tools);
      callback=new LazyDelegationCallback(factory.getBeanProvider(SubAgentTools.class),name);
    } else callback=tools.callback(name);
    assertThat(DeliveryToolRegistration.kind(callback)).isEqualTo(ResultDelivery.Kind.LOSSLESS_RESULT);
    assertThat(((DeliveryToolRegistration.Registered)callback).reviewPage()).isFalse();
    assertThat(callback.getToolDefinition().description()).contains("readRawToolResult","nextOffset","fragment");
    callback=StableToolDefinitions.callbacks(List.of(new ToolEventCallbackDecorator(callback,
        new AgentEventFactory(Clock.systemUTC()),event->{}))).getFirst();
    assertThat(DeliveryToolRegistration.kind(callback)).isEqualTo(ResultDelivery.Kind.LOSSLESS_RESULT);
    var prompt=new Prompt(List.of(new UserMessage("review")),ToolCallingChatOptions.builder().toolCallbacks(List.of(callback)).build());
    var response=new ChatResponse(List.of(new Generation(AssistantMessage.builder()
        .toolCalls(List.of(new AssistantMessage.ToolCall("call","function",name,arguments))).build())));
    var history=new ToolLoopSupport().execute(prompt,response).conversationHistory();
    var output=(ToolResponseMessage)history.getLast();
    assertThat(ToolResultCompressor.policy(output,"call")).isEqualTo(ResultDelivery.Kind.LOSSLESS_RESULT);
    assertThat(output.getMetadata()).doesNotContainKey("rei.reviewReceipts");
    String raw=output.getResponses().getFirst().responseData();
    var original=parser.parse(raw);
    if(name.equals("delegateTask"))assertThat(original.at("/structuredOutput/result/findings/2").asString()).isEqualTo("last");
    else {
      assertThat(original.at("/items/0/id").asString()).isEqualTo("first");
      assertThat(original.at("/items/1/id").asString()).isEqualTo("second");
      assertThat(original.at("/items/1/result/structuredOutput/result/findings/2").asString()).isEqualTo("last");
    }
    var store=new RawToolResultStore(directory.resolve("raw"));
    var paged=(ToolResponseMessage)new ToolResultCompressor(store,TokenEstimator.conservative(),100,70)
        .compact(output,"chat","run");
    var page=parser.parse(paged.getResponses().getFirst().responseData());
    assertThat(page.path("truncated").asBoolean()).isTrue();
    assertThat(page.path("content").asString()).isEqualTo(raw.substring(0,page.path("nextOffset").asInt()));
    assertThat(store.read("chat",page.path("rawResultRef").asString()).rawResult()).isEqualTo(raw);
    if(name.equals("delegateTask"))verify(runner).run("reviewer","review",null);
    else verify(parallel).delegate(anyList());
  }
}
