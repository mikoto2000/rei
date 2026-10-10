package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class AutomaticRetentionTest {
  @TempDir Path root;final RetentionExecutionTest.MutableClock clock=new RetentionExecutionTest.MutableClock();StorageObjectRegistry registry;RetentionPlanner planner;AutomaticRetention automatic;RetentionExecutor executor;
  @BeforeEach void prepare()throws Exception {try(var gate=new StorageMigrationCoordinator(root)){gate.prepare();}registry=new StorageObjectRegistry(root,clock);planner=new RetentionPlanner(registry);executor=new RetentionExecutor(registry,planner);automatic=new AutomaticRetention(registry,planner,executor);}
  Path archive(String project)throws Exception {Path file=(project==null?root:root.resolve("projects").resolve(project)).resolve("logs/activity-archives/"+UUID.randomUUID()+".jsonl");Files.createDirectories(file.getParent());Files.writeString(file,"old diagnostic\n");registry.registerProducedFile(file,"ACTIVITY_RAW",project,null,clock.instant().minus(Duration.ofDays(100)),true,List.of());return file;}
  @Test void defaultOffAndProposalWithoutApprovalNeverRunsAndApprovedScopeHasFixedBudget()throws Exception {
    Path global=archive(null);String project=UUID.randomUUID().toString();Path one=archive(project),two=archive(project);automatic.tick();assertThat(global).exists();assertThat(one).exists();
    var consent=automatic.propose("activity-raw",project,1,1024,Duration.ofMinutes(10));automatic.tick();assertThat(one).exists();assertThat(two).exists();assertThat(consent.policy().retentionSeconds()).isEqualTo(Duration.ofDays(90).getSeconds());
    automatic.approve(consent.id());automatic.tick();assertThat(executor.history()).hasSize(1);assertThat(Files.exists(one)&&Files.exists(two)).isFalse();assertThat(Files.exists(one)||Files.exists(two)).isTrue();assertThat(global).exists();
    automatic.tick();assertThat(executor.history()).hasSize(1);clock.time=clock.time.plus(Duration.ofMinutes(11));automatic.tick();assertThat(executor.history()).hasSize(2);
    assertThat(executor.history()).allSatisfy(execution->assertThat(execution.status()).isEqualTo("QUARANTINED"));automatic.disable(consent.id());clock.time=clock.time.plus(Duration.ofDays(100));automatic.tick();assertThat(executor.history()).hasSize(2);
  }
  @Test void changingPolicyInvalidatesProposalAndExistingConsent()throws Exception {
    var stale=automatic.propose("activity-raw",null,1,1024,Duration.ofMinutes(10));planner.updatePolicy("activity-raw",Duration.ofDays(100),null,null);
    assertThatThrownBy(()->automatic.approve(stale.id())).isInstanceOf(IllegalStateException.class);
    var approved=automatic.propose("activity-raw",null,1,1024,Duration.ofMinutes(10));automatic.approve(approved.id());Path file=archive(null);planner.updatePolicy("activity-raw",Duration.ofDays(101),null,null);automatic.tick();assertThat(file).exists();assertThat(executor.history()).isEmpty();
  }
}
