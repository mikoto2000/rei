package dev.mikoto2000.rei.memory.service;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import dev.mikoto2000.rei.memory.configuration.*;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class MemoryConsolidationBudgetTest {
  @TempDir Path root;
  ChatModel model;
  MemoryConsolidatorService service(int calls,long tokens,ChatResponse... responses) {
    var data=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve(UUID.randomUUID()+".db"));
    var jdbc=JdbcClient.create(data);
    jdbc.sql("CREATE TABLE SPRING_AI_CHAT_MEMORY(type TEXT,content TEXT,timestamp TEXT)").update();
    jdbc.sql("INSERT INTO SPRING_AI_CHAT_MEMORY VALUES('USER','use Java','2026-10-06')").update();
    model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(responses[0],Arrays.copyOfRange(responses,1,responses.length));
    var props=new MemoryProperties(true,20,80,10,3,2000,60,null);
    var service=new MemoryConsolidatorService(ChatClient.create(model),data,props);
    service.setConsolidationProperties(new MemoryConsolidationProperties(calls,tokens));return service;
  }
  ChatResponse response(String text,Integer tokens) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),ChatResponseMetadata.builder()
        .usage(tokens==null?new EmptyUsage():new DefaultUsage(tokens,0)).build());
  }
  String candidates(){return "[{\"content\":\"use Java\"}]";}
  @Test void extractAndSummarizeShareOneCallBudget() {
    var service=service(1,0,response(candidates(),4),response("summary",4));
    var stopped=assertThrows(ExecutionStoppedException.class,service::summarizeCandidates);
    assertEquals(ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED,stopped.reason());
    verify(model,times(1)).call(any(Prompt.class));
  }
  @Test void reportedUsageIsSummedAcrossBothPhasesBeforeReturningSummary() {
    var service=service(2,7,response(candidates(),4),response("summary",4));
    var stopped=assertThrows(ExecutionStoppedException.class,service::summarizeCandidates);
    assertEquals(ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED,stopped.reason());
    verify(model,times(2)).call(any(Prompt.class));
  }
  @Test void exactExtractionTokenLimitStopsTheNextCallAndUsagePrecedesFallbackAndClipping() {
    var service=service(2,4,response(candidates(),4),response("summary",1));
    assertEquals(ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED,
        assertThrows(ExecutionStoppedException.class,service::summarizeCandidates).reason());
    verify(model,times(1)).call(any(Prompt.class));
    service=service(0,3,response("invalid JSON",4));
    assertEquals(ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED,
        assertThrows(ExecutionStoppedException.class,service::extractCandidates).reason());
    service=service(0,3,response("x".repeat(2100),4));
    var limited=service;
    assertEquals(ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED,
        assertThrows(ExecutionStoppedException.class,()->limited.summarize(List.of("input"))).reason());
  }
  @Test void unknownUsageAndProviderFailureStopWithoutFallbackCandidates() {
    var service=service(0,10,response(candidates(),null));
    assertEquals(ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN,
        assertThrows(ExecutionStoppedException.class,service::extractCandidates).reason());
    service=service(0,10,response(candidates(),1));
    when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("private provider diagnostic"));
    assertEquals(ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN,
        assertThrows(ExecutionStoppedException.class,service::summarizeCandidates).reason());
    service=service(0,0,response("plain fallback",null));
    assertFalse(service.extractCandidates().isEmpty());
  }
  @Test void completedCommandsAcceptExactTotalLimitAndDirectInvocationsStartFresh() {
    var service=service(2,8,response(candidates(),4),response("summary",4));
    var summary=service.summarizeCandidates();assertTrue(summary.hasCandidates());assertEquals("summary",summary.summary());
    service=service(1,4,response("x".repeat(2100),4));
    assertEquals(2000,service.summarize(List.of("first")).length());
    assertEquals(2000,service.summarize(List.of("second")).length());verify(model,times(2)).call(any(Prompt.class));
    assertEquals("",service.summarize(List.of()));verify(model,times(2)).call(any(Prompt.class));
  }
  @Test void approvedCommandsDoNotSaveAfterBudgetStop(org.springframework.boot.test.system.CapturedOutput output) {
    var service=service(1,0,response(candidates(),4),response("summary",4));
    var memories=mock(MemoryService.class);
    new picocli.CommandLine(new dev.mikoto2000.rei.memory.command.MemoryCommand.SummarizeCommand(service,memories)).execute("--approve");
    verifyNoInteractions(memories);verify(model,times(1)).call(any(Prompt.class));
    assertTrue(output.getOut().contains("[stopped] LLM_CALL_BUDGET_EXCEEDED"));
    service=service(0,1,response(candidates(),null));
    new picocli.CommandLine(new dev.mikoto2000.rei.memory.command.MemoryCommand.ConsolidateCommand(service,memories,
        mock(dev.mikoto2000.rei.memory.util.SensitiveInfoDetector.class),mock(MemoryConflictResolver.class))).execute("--approve");
    verifyNoInteractions(memories);assertTrue(output.getOut().contains("[stopped] TOKEN_USAGE_UNKNOWN"));
    assertFalse(output.getOut().contains("private provider diagnostic"));
  }
  @Test void approvedCompleteSummaryKeepsTheExistingSaveContract() {
    var service=service(2,8,response(candidates(),4),response("summary",4));
    var memories=mock(MemoryService.class);
    new picocli.CommandLine(new dev.mikoto2000.rei.memory.command.MemoryCommand.SummarizeCommand(service,memories)).execute("--approve");
    var saved=org.mockito.ArgumentCaptor.forClass(dev.mikoto2000.rei.memory.model.Memory.class);
    verify(memories).save(saved.capture());assertEquals("summary",saved.getValue().content());
    assertEquals(dev.mikoto2000.rei.memory.model.MemoryStatus.CANDIDATE,saved.getValue().status());
  }
  @Test void cancellationIsPreservedBeforeInvocationAndFromWrappedProviderInterruption() {
    var service=service(0,10,response(candidates(),1));
    Thread.currentThread().interrupt();
    try{assertThrows(java.util.concurrent.CancellationException.class,service::summarizeCandidates);verify(model,never()).call(any(Prompt.class));}
    finally{Thread.interrupted();}
    when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException(new InterruptedException()));
    try{assertThrows(java.util.concurrent.CancellationException.class,service::summarizeCandidates);assertTrue(Thread.currentThread().isInterrupted());}
    finally{Thread.interrupted();}
  }
  @Test void consolidationSettingsBindWithIndependentZeroDefaults() {
    var defaults=new MemoryConsolidationProperties(0,0);assertEquals(0,defaults.maxTotalTokens());
    var bound=new org.springframework.boot.context.properties.bind.Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
        "rei.memory.consolidation.max-llm-calls","2","rei.memory.consolidation.max-total-tokens","50")))
        .bind("rei.memory.consolidation",MemoryConsolidationProperties.class).get();
    assertEquals(2,bound.maxLlmCalls());assertEquals(50,bound.maxTotalTokens());
    assertThrows(IllegalArgumentException.class,()->new MemoryConsolidationProperties(1001,0));
    assertThrows(IllegalArgumentException.class,()->new MemoryConsolidationProperties(0,-1));
  }
}
