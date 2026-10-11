package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

@Tag("integration")
class SlackAgentRepositoryTest {
  @TempDir Path dir;
  final Instant now=Instant.parse("2026-10-11T00:00:00Z");
  SlackAgentRepository repo(){return new SlackAgentRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("storage.db")),Clock.fixed(now,ZoneOffset.UTC));}
  AgentRunContext owner(){return new AgentRunContext("run","session",dir,"project");}
  SlackAgentProperties settings(){var p=new SlackAgentProperties();p.setEnabled(true);p.setAllowedWorkspaceIds(Set.of("T1"));p.setAllowedChannelIds(Set.of("C1"));p.setAgents(Map.of("tenten",new SlackAgentProperties.Agent("T1","U2","B2","C1","U2","")));return p;}
  SlackAgentRepository.Request create(SlackAgentRepository r,String call){return r.create(owner(),call,"tenten","question "+call,"expected",300,null,settings());}
  @Test void creationIsIdempotentAndBudgetIsDurable(){var r=repo();var first=create(r,"call");assertEquals(first.id(),create(r,"call").id());assertEquals("CREATED",first.state());create(r,"two");create(r,"three");assertThrows(IllegalArgumentException.class,()->create(repo(),"four"));assertThrows(IllegalArgumentException.class,()->r.create(owner(),"x","other","q","",300,null,settings()));}
  @Test void sendAndAnswerAreDifferentStatesAndTransitionsAreFenced(){var r=repo();var q=create(r,"call");assertTrue(r.claimSend(q.id()));assertFalse(r.claimSend(q.id()));r.sent(q.id(),"100.1");assertEquals("WAITING",r.get(q.id()).state());assertThrows(IllegalStateException.class,()->r.sent(q.id(),"100.2"));assertTrue(r.receive(message("E1","101.1","answer"),"U1",settings()));assertEquals("WAITING",r.get(q.id()).state());assertFalse(r.receive(message("E1","101.1","answer"),"U1",settings()));assertEquals(1,r.responses(q.id()).size());}
  SlackMessage message(String event,String ts,String text){return new SlackMessage(event,"T1","C1",ts,"100.1","U2","B2","bot_message","message","channel",text);}
  @Test void exactConfiguredMarkerCanCompleteButSilenceCannot(){var p=settings();p.setAgents(Map.of("tenten",new SlackAgentProperties.Agent("T1","U2","B2","C1","U2","FINAL:")));var r=repo();var q=r.create(owner(),"call","tenten","q","",300,null,p);r.claimSend(q.id());r.sent(q.id(),"100.1");r.receive(message("ack","101.1","working"),"U1",p);assertEquals("WAITING",r.get(q.id()).state());r.receive(message("final","102.1","FINAL: result"),"U1",p);assertEquals("COMPLETED",r.get(q.id()).state());assertEquals("configured_final_prefix",r.get(q.id()).reason());}
  @Test void terminalCancellationRejectsLateReplyAndTimeoutRetainsPartial(){var r=repo();var q=create(r,"call");r.claimSend(q.id());r.sent(q.id(),"100.1");r.receive(message("ack","101.1","partial"),"U1",settings());r.expire(now.plusSeconds(301));assertEquals("TIMED_OUT",r.get(q.id()).state());assertEquals("partial",r.responses(q.id()).getFirst().text());assertFalse(r.receive(message("late","102.1","late"),"U1",settings()));var other=create(r,"two");r.cancelRun("run");assertEquals("CANCELLED",r.get(other.id()).state());}
  @Test void uncertainSendIsNeverAutomaticallyReplayed(){var r=repo();var q=create(r,"call");r.claimSend(q.id());repo().recover();assertEquals("UNKNOWN",r.get(q.id()).state());assertFalse(r.claimSend(q.id()));}
  @Test void untrustedIdentityAndUnrelatedThreadsAreRejected(){var r=repo();var q=create(r,"call");r.claimSend(q.id());r.sent(q.id(),"100.1");for(var m:List.of(new SlackMessage("x","T2","C1","101","100.1","U2","B2","bot_message","message","channel","x"),new SlackMessage("x","T1","C2","101","100.1","U2","B2","bot_message","message","channel","x"),new SlackMessage("x","T1","C1","101","other","U2","B2","bot_message","message","channel","x"),new SlackMessage("x","T1","C1","101","100.1","U3","B3","bot_message","message","channel","I am B2"),new SlackMessage("x","T1","C1","101","100.1","U2","B2","message_changed","message","channel","x")))assertFalse(r.receive(m,"U1",settings()));assertFalse(r.receive(message("self","101","x"),"U2",settings()));assertTrue(r.responses(q.id()).isEmpty());}
}
