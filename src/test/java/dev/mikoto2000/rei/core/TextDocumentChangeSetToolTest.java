package dev.mikoto2000.rei.core;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.*;
class TextDocumentChangeSetToolTest {
  @TempDir Path root;
  @Test void extendedSingleFileServiceAndExistingToolsUseCapturedOwnerAndOneCommittedEvent()throws Exception{
    var owner=new AgentRunContext("run","session",root,UUID.randomUUID().toString());var repository=new TextChangeSetRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("changes.db")));var single=new TextChangeSetService(repository);single.setDocuments(new TextDocumentChangeSetService(repository,Clock.systemUTC(),TextDocumentTransaction::replace));
    var factory=org.mockito.Mockito.mock(dev.mikoto2000.rei.event.AgentEventFactory.class);var publisher=org.mockito.Mockito.mock(dev.mikoto2000.rei.event.AgentEventPublisher.class);var tools=new Tools(null,new dev.mikoto2000.rei.core.service.SystemShellService(),null,Clock.systemUTC(),new dev.mikoto2000.rei.core.working.WorkingSet(),factory,publisher);tools.setTextChangeSets(single);Files.writeString(root.resolve("A.md"),"old");
    try(var scope=AgentRunScope.open(owner)){var proposal=tools.proposeTextDocumentChangeSet(new TextDocumentChangeSetService.Request(List.of(new TextDocumentChangeSetService.Operation("UPDATE","A.md",null,"old","new"))));assertEquals("PROPOSED",tools.inspectTextDocumentChangeSet(proposal.id()).status());assertTrue(tools.applyTextDocumentChangeSet(proposal.id(),proposal.proposalSha256()).currentMatches());assertTrue(tools.applyTextDocumentChangeSet(proposal.id(),proposal.proposalSha256()).currentMatches());}
    var policy=new ToolPermissionPolicy(new ToolPermissionProperties(true,null,null,null));assertEquals(Set.of(ActionCapability.READ),policy.capabilities("inspectTextDocumentChangeSet"));assertEquals(Set.of(ActionCapability.LOCAL_WRITE),policy.capabilities("proposeTextDocumentChangeSet"));assertEquals(Set.of(ActionCapability.LOCAL_WRITE,ActionCapability.DESTRUCTIVE),policy.capabilities("applyTextDocumentChangeSet"));
    org.mockito.Mockito.verify(factory,org.mockito.Mockito.times(1)).fileModified(root.resolve("A.md").toString(),null,null);
  }
  @Test void humanRecoveryCommandRoutesExactInstructionThroughExistingExclusiveQueue(){
    var conversations=org.mockito.Mockito.mock(dev.mikoto2000.rei.application.session.ShellConversationService.class);var cli=new picocli.CommandLine(new dev.mikoto2000.rei.core.command.DocumentCommand(conversations));String id=UUID.randomUUID().toString(),sha="a".repeat(64);assertEquals(0,cli.execute("rollback",id,sha));org.mockito.Mockito.verify(conversations).submit("/document rollback "+id+" "+sha);assertEquals(2,cli.execute("auto",id,sha));org.mockito.Mockito.verifyNoMoreInteractions(conversations);
    assertTrue(Arrays.asList(dev.mikoto2000.rei.ui.shell.RootCommand.class.getAnnotation(picocli.CommandLine.Command.class).subcommands()).contains(dev.mikoto2000.rei.core.command.DocumentCommand.class));
  }
}
