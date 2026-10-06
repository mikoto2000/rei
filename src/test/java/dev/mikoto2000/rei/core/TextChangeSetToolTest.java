package dev.mikoto2000.rei.core;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TextChangeSetToolTest {
  @TempDir Path root;
  @Test void toolsRouteSavedProposalThroughExistingFileEventBoundaryAndPolicy() throws Exception {
    var id=UUID.randomUUID().toString();
    var projects=mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    when(projects.currentContext()).thenReturn(new dev.mikoto2000.rei.core.project.ProjectContext(id,"rei",root));
    when(projects.currentProject()).thenReturn(root);
    var publisher=mock(dev.mikoto2000.rei.event.AgentEventPublisher.class);
    var factory=mock(dev.mikoto2000.rei.event.AgentEventFactory.class);
    var tools=new Tools(projects,new dev.mikoto2000.rei.core.service.SystemShellService(),
        new dev.mikoto2000.rei.core.process.BackgroundProcessManager(new dev.mikoto2000.rei.core.service.SystemShellService()),
        Clock.systemUTC(),new dev.mikoto2000.rei.core.working.WorkingSet(),factory,publisher);
    var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("changes.db"));
    tools.setTextChangeSets(new TextChangeSetService(new TextChangeSetRepository(ds)));
    Files.writeString(root.resolve("note.md"),"old");
    var baseline=tools.readTextChangeSetBase("note.md");
    var proposal=tools.proposeTextChangeSet(new TextChangeSetService.Request(baseline.path(),baseline.text(),"new"));
    verifyNoInteractions(factory,publisher);
    assertEquals(proposal,tools.inspectTextChangeSet(proposal.id()));
    assertEquals("APPLIED",tools.applyTextChangeSet(proposal.id(),proposal.proposalSha256()).status());
    verify(factory).fileModified(root.resolve("note.md").toString(),null,null);
    var callbacks=Arrays.stream(org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()).map(c->c.getToolDefinition().name()).toList();
    assertTrue(callbacks.containsAll(List.of("readTextChangeSetBase","proposeTextChangeSet","inspectTextChangeSet","applyTextChangeSet","discardTextChangeSet")));
    var policy=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(false,null,null,null));
    assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),policy.capabilities("inspectTextChangeSet"));
    assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),policy.capabilities("readTextChangeSetBase"));
    assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.LOCAL_WRITE),policy.capabilities("proposeTextChangeSet"));
    assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.LOCAL_WRITE),policy.capabilities("applyTextChangeSet"));
    assertThrows(IllegalArgumentException.class,()->new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(Set.of("applyTextChangeSet")).validate(List.of("applyTextChangeSet")));
  }
}
