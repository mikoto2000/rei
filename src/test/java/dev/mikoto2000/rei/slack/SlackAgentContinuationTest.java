package dev.mikoto2000.rei.slack;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.chat.messages.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

@Tag("integration")
class SlackAgentContinuationTest {
  @TempDir Path dir;
  SlackAgentContinuationStore store(){return new SlackAgentContinuationStore(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("storage.db")));}
  AgentRunContext owner(){return new AgentRunContext("run","session",dir,"project");}
  List<Message> messages(){return List.of(new SystemMessage("never persist system"),new UserMessage("original request"),AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call","function","askExternalAgent","{}"))).build(),ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call","askExternalAgent","WAITING"))).build());}
  @Test void restartRestoresSameOwnerAndStructuredToolHistoryButNeverSystemMessages(){var s=store();s.save(owner(),"original request",messages(),new OutputLimitRunBudget.Snapshot(1,2,100,false));var restored=store().get(owner());assertEquals(2,restored.budget().llmCalls());assertEquals(100,restored.budget().totalTokens());assertEquals(3,restored.messages().size());assertInstanceOf(ToolResponseMessage.class,restored.messages().getLast());assertThrows(IllegalArgumentException.class,()->s.get(new AgentRunContext("run","other",dir,"project")));}
  @Test void durableResumeClaimIsOneShotAndOwnerLossNeverReplays(){var s=store();s.save(owner(),"request",messages(),new OutputLimitRunBudget.Snapshot(0,1,0,false));assertTrue(s.claim("run"));assertFalse(store().claim("run"));store().recover();assertEquals("UNKNOWN",s.state("run"));assertFalse(s.claim("run"));}
  @Test void cancellationMakesSavedContinuationIneligible(){var s=store();s.save(owner(),"request",messages(),new OutputLimitRunBudget.Snapshot(0,1,0,false));s.cancel("run");assertFalse(s.claim("run"));assertEquals("CANCELLED",s.state("run"));}
  @Test void budgetRestoreNeverReplenishesCallsTokensOrReplans(){var b=new OutputLimitRunBudget(2,3,null,100);b.restore(new OutputLimitRunBudget.Snapshot(2,2,90,false));assertEquals(1,b.remainingLlmCalls());assertFalse(b.tryConsumeReplan());assertTrue(b.tryConsumeLlmCall());assertFalse(b.tryConsumeLlmCall());b.recordTotalTokens(10);assertTrue(b.tokenExhausted());}
  @Test void registryWaitingSurvivesRestartAndSameRunCanResume(){var source=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("runs.db"));var r=new RunRegistry(Clock.systemUTC(),source);r.register(owner());r.transition("run",RunStatus.RUNNING,null);r.transition("run",RunStatus.WAITING,null);r.close();var restored=new RunRegistry(Clock.systemUTC(),source);assertEquals(RunStatus.WAITING,restored.get("run").status());assertTrue(restored.claimWaiting(owner()));assertTrue(restored.transition("run",RunStatus.RUNNING,null));assertFalse(restored.claimWaiting(owner()));}
}
