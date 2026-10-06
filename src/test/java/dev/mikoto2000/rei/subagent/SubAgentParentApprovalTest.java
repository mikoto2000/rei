package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.event.AgentEventFactory;
import reactor.core.publisher.Flux;

@org.junit.jupiter.api.Tag("integration")
class SubAgentParentApprovalTest {
  @TempDir Path directory;
  @Test void realRunnerUsesCapturedParentOnlyWhenDefinitionOptsInAndNeverReplaysGrant() throws Exception {
    var repository=new ToolApprovalRepository(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("approval.db")),Clock.systemUTC());
    var parent=new AgentRunContext("parent","session",directory,"project");
    var request=repository.request("readMultiFile","{}",parent);repository.decide("project",request.id(),true);
    var guard=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(),Set.of(),Map.of())),
        new AgentEventFactory(Clock.systemUTC()),event->{});guard.setApprovals(repository);
    for(int attempt=0;attempt<3;attempt++) {
      var fixture=new SubAgentRunnerTest();fixture.directory=directory;
      fixture.requiredCallsConfiguration="inheritApprovals: "+(attempt>0)+"\n";
      var calls=new AtomicInteger();
      var runner=fixture.runner(prompt->Flux.just(calls.incrementAndGet()==1
          ?fixture.tool("readMultiFile"):fixture.answer(SubAgentResultParserTest.VALID)),"2s");
      runner.setToolPermissionGuard(guard);
      try(var scope=AgentRunScope.open(parent)) {
        var result=runner.run("reviewer","inspect",null);
        assertThat(result.status()).isEqualTo(attempt==1?SubAgentResult.Status.COMPLETED:SubAgentResult.Status.FAILED);
        assertThat(fixture.toolCalls).hasValue(attempt==1?1:0);
      }
      assertThat(repository.get("project",request.id()).status()).isEqualTo(attempt==0?"APPROVED":"CONSUMED");
    }
  }
}
