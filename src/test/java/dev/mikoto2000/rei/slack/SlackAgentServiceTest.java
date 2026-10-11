package dev.mikoto2000.rei.slack;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.chat.messages.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

@Tag("integration")
class SlackAgentServiceTest {
  @TempDir Path dir;
  final Clock clock=Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"),ZoneOffset.UTC);
  AgentRunContext owner(){return new AgentRunContext("run","session",dir,"project");}
  SlackAgentProperties settings(){var p=new SlackAgentProperties();p.setEnabled(true);p.setAllowedWorkspaceIds(Set.of("T1"));p.setAllowedChannelIds(Set.of("C1"));p.setAgents(Map.of("tenten",new SlackAgentProperties.Agent("T1","U2","B2","C1","U2","FINAL:")));return p;}
  record Fixture(SlackAgentRepository repo,SlackAgentContinuationStore saved,RunRegistry registry,SlackAgentService service) {}
  Fixture fixture(){var source=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("storage.db"));var repo=new SlackAgentRepository(source,clock);var saved=new SlackAgentContinuationStore(source);var registry=new RunRegistry(clock);registry.register(owner());registry.transition("run",RunStatus.RUNNING,null);var bus=new InMemoryAgentEventBus();var router=new ConversationInputRouter(Runnable::run,(o,p,q)->{});var runs=new RunService(registry,bus,new AgentEventFactory(clock),new dev.mikoto2000.rei.core.service.CommandCancellationService(),router::cancelQueued);var service=new SlackAgentService(settings(),repo,saved,registry,runs,router,clock);return new Fixture(repo,saved,registry,service);}
  void pause(Fixture f){var e=new RunExecutionContext("run",new OutputLimitRunBudget(1,3),new ProgressEvaluator(dir,null),new AgentEventFactory(clock),event->{});e.setRunContext(owner());e.setUserRequest("original request");var history=List.<Message>of(new UserMessage("original request"),AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call","function","askExternalAgent","{}"))).build(),ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call","askExternalAgent","WAITING"))).build());assertTrue(f.service().suspend(e,history));assertEquals(RunStatus.WAITING,f.registry().get("run").status());}
  @Test void replyResumesSameParentOnceWithUntrustedToolResult(){var f=fixture();var q=f.repo().create(owner(),"call","tenten","question","",300,null,settings());pause(f);var sends=new AtomicInteger();var resumes=new AtomicInteger();f.service().attach("U1","T1",row->{sends.incrementAndGet();assertNull(row.thread());assertTrue(row.text().startsWith("<@U2>"));return SlackTransport.Delivery.sent("100.1");});f.service().onResume((o,saved)->{assertEquals(owner(),o);assertInstanceOf(ToolResponseMessage.class,saved.messages().getLast());assertTrue(saved.messages().getLast().getText()==null||!saved.messages().getLast().getText().contains("FINAL:"));var data=((ToolResponseMessage)saved.messages().getLast()).getResponses().getFirst().responseData();assertTrue(data.contains("UNTRUSTED DATA"));assertTrue(data.contains("result"));resumes.incrementAndGet();return ChatExecutionResult.success("integrated",false);});f.service().pump();assertEquals("WAITING",f.repo().get(q.id()).state());assertEquals(0,resumes.get());assertTrue(f.service().receive(new SlackMessage("E1","T1","C1","101.1","100.1","U2","B2","bot_message","message","channel","FINAL: result")));f.service().pump();f.service().pump();assertEquals(1,resumes.get());assertEquals(1,sends.get());assertEquals(Set.of("run"),f.registry().runIds());assertEquals(RunStatus.COMPLETED,f.registry().get("run").status());}
  @Test void cancelAfterSendingNeverResumesOnLateReply(){var f=fixture();f.repo().create(owner(),"call","tenten","question","",300,null,settings());pause(f);f.service().attach("U1","T1",row->SlackTransport.Delivery.sent("100.1"));f.service().pump();f.service().cancel("run");assertFalse(f.service().receive(new SlackMessage("late","T1","C1","101.1","100.1","U2","B2","bot_message","message","channel","FINAL: late")));f.service().onResume((o,s)->{fail("Cancelled Run resumed");return null;});f.service().pump();assertEquals("CANCELLED",f.saved().state("run"));}
  @Test void unknownSendIsReportedWithoutReposting(){var f=fixture();var q=f.repo().create(owner(),"call","tenten","question","",300,null,settings());pause(f);var sends=new AtomicInteger();f.service().attach("U1","T1",row->{sends.incrementAndGet();return SlackTransport.Delivery.unknown();});f.service().onResume((o,s)->{assertTrue(((ToolResponseMessage)s.messages().getLast()).getResponses().getFirst().responseData().contains("UNKNOWN"));return ChatExecutionResult.success("uncertain",false);});f.service().pump();f.service().pump();assertEquals(1,sends.get());assertEquals("UNKNOWN",f.repo().get(q.id()).state());}
}
