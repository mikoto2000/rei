package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;

@Tag("integration")
class DelegatedToolApprovalTest {
  @TempDir Path directory;
  ToolApprovalRepository repository;
  ToolPermissionGuard guard;
  AgentRunContext parent,child;
  @BeforeEach void setup() {
    var clock=Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"),ZoneOffset.UTC);
    repository=new ToolApprovalRepository(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("approval.db")),clock);
    guard=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(),Set.of(),Map.of())),
        new AgentEventFactory(clock),event->{});guard.setApprovals(repository);
    parent=new AgentRunContext("parent","session",directory,"project");
    child=new AgentRunContext("child","subagent:child",directory,"project");
  }
  @Test void parentGrantIsExactAndConsumedOnceByChild() {
    var request=repository.request("readMultiFile","{}",parent);repository.decide("project",request.id(),true);
    assertThrows(ToolPermissionException.class,()->guard.check("readMultiFile","{}",child));
    assertThrows(ToolPermissionException.class,()->guard.checkDelegated("readMultiFile","{ }",child,parent));
    assertEquals("APPROVED",repository.get("project",request.id()).status());
    assertDoesNotThrow(()->guard.checkDelegated("readMultiFile","{}",child,parent));
    assertEquals("CONSUMED",repository.get("project",request.id()).status());
    assertThrows(ToolPermissionException.class,()->guard.checkDelegated("readMultiFile","{}",child,parent));
  }
  @Test void mismatchAndNonReadActionsCannotBorrowParentGrant() {
    var request=repository.request("readMultiFile","{}",parent);repository.decide("project",request.id(),true);
    for(var wrong:List.of(new AgentRunContext("child","subagent:child",directory,"other"),
        new AgentRunContext("child","subagent:child",directory.resolve("other"),"project"),
        new AgentRunContext("child","ordinary",directory,"project"))) {
      assertThrows(ToolPermissionException.class,()->guard.checkDelegated("readMultiFile","{}",wrong,parent));
    }
    var write=repository.request("writeMultiFile","{}",parent);repository.decide("project",write.id(),true);
    assertThrows(ToolPermissionException.class,()->guard.checkDelegated("writeMultiFile","{}",child,parent));
    assertEquals("APPROVED",repository.get("project",request.id()).status());
    assertEquals("APPROVED",repository.get("project",write.id()).status());
  }
  @Test void missingGrantCreatesReviewableRequestOwnedByParentSession() {
    assertThrows(ToolPermissionException.class,()->guard.checkDelegated("readMultiFile","{}",child,parent));
    var request=repository.list("project").getFirst();
    assertEquals(parent.conversationId(),request.sessionId());assertEquals(parent.runId(),request.runId());
    repository.decide("project",request.id(),true);
    assertDoesNotThrow(()->guard.checkDelegated("readMultiFile","{}",child,parent));
  }
  @Test void explicitDenialStillWinsAndConcurrentChildrenShareOneGrant() throws Exception {
    var request=repository.request("readMultiFile","{}",parent);repository.decide("project",request.id(),true);
    var denying=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(),Set.of(ActionCapability.READ),Map.of())),
        new AgentEventFactory(Clock.systemUTC()),event->{});denying.setApprovals(repository);
    assertThrows(ToolPermissionException.class,()->denying.checkDelegated("readMultiFile","{}",child,parent));
    assertEquals("APPROVED",repository.get("project",request.id()).status());
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first=executor.submit(()->{try{guard.checkDelegated("readMultiFile","{}",child,parent);return true;}catch(ToolPermissionException refused){return false;}});
      var second=executor.submit(()->{try{guard.checkDelegated("readMultiFile","{}",new AgentRunContext("other","subagent:other",directory,"project"),parent);return true;}catch(ToolPermissionException refused){return false;}});
      assertNotEquals(first.get(),second.get());
    }
    assertEquals("CONSUMED",repository.get("project",request.id()).status());
  }
}
