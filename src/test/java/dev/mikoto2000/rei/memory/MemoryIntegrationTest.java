package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.contextbudget.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.core.working.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.memory.service.*;
import reactor.core.publisher.Flux;

@org.junit.jupiter.api.Tag("integration")
class MemoryIntegrationTest {
  @TempDir Path dir;
  @Test void chatCompressionRetainsMemoryWithoutWritingItToHistoryOrSummary() {
    var properties=new MemoryProperties(true,20,80,10,3,2000,60,null);
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("memory.db"));
    var repository=new MemoryRepository(ds,new MemoryService(ds,properties));
    repository.insert(LongTermMemoryTest.candidate("Vision: long term reference",MemoryScope.PROJECT),"00000000-0000-0000-0000-000000000001","previous-session");
    var turns=new ConversationTurnStore(dir);
    var old=new AgentRunContext("old","project:00000000-0000-0000-0000-000000000001:chat",dir,"00000000-0000-0000-0000-000000000001");
    turns.start(old,"historic text ".repeat(400)); turns.finish(old,ConversationTurnStore.Status.COMPLETED,"recent reply");
    var owner=new AgentRunContext("current","project:00000000-0000-0000-0000-000000000001:chat",dir,"00000000-0000-0000-0000-000000000001");
    var events=new AgentEventFactory(Clock.systemUTC());
    var observed=new ArrayList<AgentEvent>();
    var summaries=new ConversationSummaryRepository(dir);
    var compression=new ContextCompressionProperties();
    compression.setThreshold(500); compression.setHardLimit(1000); compression.setRecentTokens(100); compression.setSummaryTokens(100);
    var tokens=TokenEstimator.conservative();
    var assembler=new ContextAssembler(compression,tokens,summaries,
        new ToolResultCompressor(new RawToolResultStore(dir),tokens,1000,200),
        (previous,messages,budget,prompt) -> {
          assertTrue(messages.stream().noneMatch(m -> m.getText().contains("long term reference")));
          return "Summary of earlier conversation";
        },events,observed::add);
    var working=new WorkingSet(20,Clock.systemUTC()); working.recordRead(dir.resolve("edited.java"));
    assembler.setWorkingSet(working::renderForPrompt);
    var execution=new RunExecutionContext("current",new OutputLimitRunBudget(2,10),mock(ProgressEvaluator.class),events,observed::add);
    execution.setRunContext(owner);
    var sent=new AtomicReference<Prompt>();
    var model=mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenAnswer(a -> { sent.set(a.getArgument(0)); return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done"))))); });
    var history=MessageWindowChatMemory.builder().maxMessages(100).build();
    var retriever=spy(new MemoryRetriever(repository,properties));
    var client=ChatClient.builder(new StagnationChatModel(model,assembler)).defaultSystem("System rules")
        .defaultAdvisors(RunScopedAdvisor.wrap(List.of(new ContextHistoryAdvisor(turns,history,summaries),
            new dev.mikoto2000.rei.temporal.RuntimeContextAdvisor(Clock.systemUTC()),
            new MemoryContextAdvisor(retriever),new WorkingSetAdvisor(working)))).build();
    var options=OpenAiChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY,execution)).build();
    client.prompt(new Prompt(new UserMessage("Vision"),options)).advisors(a -> a.param(AgentRunContext.class.getName(),owner)
        .param(ChatMemory.CONVERSATION_ID,"project:00000000-0000-0000-0000-000000000001:chat")).stream().chatResponse().blockLast();
    verify(retriever).retrieve("Vision","00000000-0000-0000-0000-000000000001");
    assertTrue(sent.get().getContents().contains("long term reference"));
    assertTrue(sent.get().getContents().contains("Summary of earlier conversation"),sent.get().getContents());
    assertTrue(sent.get().getContents().contains("edited.java"));
    assertTrue(tokens.text(sent.get().getContents())<1000);
    assertFalse(history.get("project:00000000-0000-0000-0000-000000000001:chat").toString().contains("long term reference"));
    assertFalse(summaries.read("project:00000000-0000-0000-0000-000000000001:chat").summary().contains("long term reference"));
    assertEquals(1,turns.read("project:00000000-0000-0000-0000-000000000001:chat").size());
  }
  @Test void llmUsesMemoryFeatureWithoutToolsAndValidatesStructuredResult() {
    var models=mock(LlmModelProvider.class); var model=mock(ChatModel.class);
    when(models.memoryChatModel()).thenReturn(model);
    when(models.chatOptions(LlmFeature.MEMORY,null)).thenReturn(OpenAiChatOptions.builder().build());
    when(model.stream(any(Prompt.class))).thenAnswer(a -> {
      Prompt prompt=a.getArgument(0);
      var options=(org.springframework.ai.model.tool.ToolCallingChatOptions)prompt.getOptions();
      assertEquals(false,options.getInternalToolExecutionEnabled());
      assertTrue(options.getToolCallbacks().isEmpty()); assertTrue(options.getToolNames().isEmpty());
      assertTrue(prompt.getSystemMessage().getText().contains("LESSON"));
      assertTrue(prompt.getSystemMessage().getText().contains("PROCEDURE"));
      return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(MemoryOutputTest.VALID)))));
    });
    var processor=new LlmMemoryProcessor(models,new MemoryProperties(true,20,80,10,3,2000,60,null));
    assertEquals(1,processor.extract(List.of()).size());
  }
  @Test void eventContainsCountsAndOwnershipWithoutMemoryText() {
    var observed=new ArrayList<AgentEvent>();
    var events=new MemoryEvents(new AgentEventFactory(Clock.systemUTC()),observed::add);
    events.publish(AgentEventType.MEMORY_SLEEP_COMPLETED,"00000000-0000-0000-0000-000000000001","s","sleep",true,2,1,1,"PREVIEW");
    var event=observed.getFirst();
    assertEquals("00000000-0000-0000-0000-000000000001",event.projectId()); assertEquals("s",event.sessionId());
    var external=dev.mikoto2000.rei.web.WebApiEventMapper.from(event,false,"");
    assertTrue(external.toString().contains("processedTurns=2"));
  }
  @Test void optionalMemoryCannotExhaustTheFinalContextBudget() {
    var props=new ContextCompressionProperties(); props.setThreshold(100); props.setHardLimit(150);
    var tokens=TokenEstimator.conservative(); var events=new AgentEventFactory(Clock.systemUTC());
    var assembler=new ContextAssembler(props,tokens,new ConversationSummaryRepository(dir),
        new ToolResultCompressor(new RawToolResultStore(dir),tokens,1000,200),(a,b,c,d)->"",events,e -> {});
    var raw=new Prompt(List.of(new SystemMessage("rules"),new UserMessage("current ".repeat(40)),
        SystemMessage.builder().text("optional memory ".repeat(40)).metadata(Map.of("rei.longTermMemory",true)).build()));
    var result=assembler.assemble(raw,"session","run",()->{});
    assertTrue(result.getContents().contains("current"));
    assertFalse(result.getContents().contains("optional memory"));
    assertTrue(tokens.text(result.getContents())<150);
  }
}
