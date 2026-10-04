package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

@Tag("integration")
class ToolApprovalRepositoryTest {
  @TempDir Path dir;
  final Instant now=Instant.parse("2026-10-04T00:00:00Z");
  ToolApprovalRepository repository;
  AgentRunContext owner(String project,String session,String run) {return new AgentRunContext(run,session,dir,project);}
  @BeforeEach void setup() {
    repository=new ToolApprovalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("approval.db")),Clock.fixed(now,ZoneOffset.UTC));
  }
  @Test void exactApprovalSurvivesRestartAndIsConsumedOnce() {
    var request=repository.request("writeMultiFile","{\"path\":\"a\"}",owner("p","s","r"));
    assertEquals(request.id(),repository.request("writeMultiFile","{\"path\":\"a\"}",owner("p","s","r")).id());
    repository.decide("p",request.id(),true);
    var reopened=new ToolApprovalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("approval.db")),Clock.fixed(now,ZoneOffset.UTC));
    assertTrue(reopened.consume("writeMultiFile","{\"path\":\"a\"}",owner("p","s","resume")));
    assertFalse(repository.consume("writeMultiFile","{\"path\":\"a\"}",owner("p","s","resume")));
  }
  @Test void approvalCannotCrossArgumentsToolProjectOrSession() {
    var request=repository.request("writeMultiFile","{}",owner("p","s","r"));repository.decide("p",request.id(),true);
    assertFalse(repository.consume("writeMultiFile","{ }",owner("p","s","r")));
    assertFalse(repository.consume("other","{}",owner("p","s","r")));
    assertFalse(repository.consume("writeMultiFile","{}",owner("q","s","r")));
    assertFalse(repository.consume("writeMultiFile","{}",owner("p","t","r")));
    assertFalse(repository.consume("writeMultiFile","{}",new AgentRunContext("r","s",dir.resolve("moved"),"p")));
    assertThrows(IllegalArgumentException.class,()->repository.decide("q",request.id(),true));
    assertTrue(repository.consume("writeMultiFile","{}",owner("p","s","r")));
  }
  @Test void denialAndExpiryCannotBeApprovedOrConsumed() {
    var request=repository.request("writeMultiFile","{}",owner("p","s","r"));repository.decide("p",request.id(),false);
    assertThrows(IllegalStateException.class,()->repository.decide("p",request.id(),true));
    assertFalse(repository.consume("writeMultiFile","{}",owner("p","s","r")));
    var expiring=repository.request("writeMultiFile","{}",owner("p","s","r2"));
    var approved=repository.request("writeMultiFile","{\"path\":\"expired\"}",owner("p","s","r3"));
    repository.decide("p",approved.id(),true);
    var later=new ToolApprovalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("approval.db")),Clock.fixed(now.plusSeconds(900),ZoneOffset.UTC));
    assertThrows(IllegalStateException.class,()->later.decide("p",expiring.id(),true));
    assertFalse(later.consume("writeMultiFile","{}",owner("p","s","r2")));
    assertFalse(later.consume("writeMultiFile","{\"path\":\"expired\"}",owner("p","s","r3")));
  }
  @Test void requestsAreReviewableButCredentialsAreRedactedAndInputsAreBounded() {
    var request=repository.request("custom","{\"api_key\":\"secret-123\",\"path\":\"a\"}",owner("p","s","r"));
    assertFalse(request.argumentsPreview().contains("secret-123"));
    assertTrue(request.argumentsPreview().contains("path"));
    assertThrows(IllegalArgumentException.class,()->repository.request("custom","a".repeat(16385),owner("p","s","r")));
  }
  @Test void concurrentClientsCannotConsumeTheSameGrantTwice() throws Exception {
    var request=repository.request("writeMultiFile","{}",owner("p","s","r"));repository.decide("p",request.id(),true);
    var other=new ToolApprovalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("approval.db")),Clock.fixed(now,ZoneOffset.UTC));
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first=executor.submit(()->repository.consume("writeMultiFile","{}",owner("p","s","r2")));
      var second=executor.submit(()->other.consume("writeMultiFile","{}",owner("p","s","r3")));
      assertNotEquals(first.get(),second.get());
    }
  }
  @Test void guardedActionCanResumeOnceButDenialAlwaysWins() {
    var automatic=new ToolPermissionPolicy(new ToolPermissionProperties(true,java.util.Set.of(),java.util.Set.of(),java.util.Map.of()));
    var events=new java.util.ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var guard=new ToolPermissionGuard(automatic,new dev.mikoto2000.rei.event.AgentEventFactory(Clock.fixed(now,ZoneOffset.UTC)),events::add);
    guard.setApprovals(repository);
    assertThrows(ToolPermissionException.class,()->guard.check("writeMultiFile","{}",owner("p","s","r")));
    var request=repository.list("p").getFirst();repository.decide("p",request.id(),true);
    var denying=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true,java.util.Set.of(),
        java.util.Set.of(ActionCapability.LOCAL_WRITE),java.util.Map.of())),new dev.mikoto2000.rei.event.AgentEventFactory(Clock.fixed(now,ZoneOffset.UTC)),events::add);
    denying.setApprovals(repository);
    assertThrows(ToolPermissionException.class,()->denying.check("writeMultiFile","{}",owner("p","s","r2")));
    assertEquals("APPROVED",repository.get("p",request.id()).status());
    assertDoesNotThrow(()->guard.check("writeMultiFile","{}",owner("p","s","r2")));
    assertThrows(ToolPermissionException.class,()->guard.check("writeMultiFile","{}",owner("p","s","r2")));
  }
}
