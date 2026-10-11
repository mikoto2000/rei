package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.conversation.SqliteConversationTurnStore;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class StorageReferenceProtectionTest {
  @TempDir Path root;
  final Instant now=Instant.parse("2026-10-01T00:00:00Z");
  void prepare()throws Exception{try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}}
  StorageObjectRegistry registry(){return new StorageObjectRegistry(root,Clock.fixed(now,ZoneOffset.UTC));}
  @Test void legacyRawBytesAndUnknownOwnershipAreRetainedAndProtected()throws Exception {
    String ref=new RawToolResultStore(root).save("chat","old-run","tool","call","旧結果");
    prepare();var registry=registry();var object=registry.rawObject("chat",ref).orElseThrow();
    assertThat(object.originVerified()).isFalse();
    assertThat(new RawToolResultStore(root,registry).read("chat",ref).rawResult()).isEqualTo("旧結果");
    var plan=new RetentionPlanner(registry).plan("raw-results",null,10,1024*1024);
    assertThat(plan.candidates()).isEmpty();assertThat(plan.protectedReasons()).containsKey("unverified-origin");
    assertThat(root.resolve(object.relativePath())).exists();
  }
  @Test void newRawResultsAreRegisteredAndActiveRunsAndCheckpointReferencesProtectThem()throws Exception {
    prepare();var registry=registry();var turns=new SqliteConversationTurnStore(root);
    var context=new AgentRunContext("run","chat",root);turns.start(context,"question",now);
    String ref=new RawToolResultStore(root,registry).save("chat","run","tool","call","結果");
    var object=registry.rawObject("chat",ref).orElseThrow();assertThat(object.originVerified()).isTrue();
    assertThat(registry.protectionReason(object)).isPresent();
    registry.addReference(object.id(),"CHECKPOINT","task:1");
    turns.finish(context,dev.mikoto2000.rei.conversation.ConversationTurnStore.Status.COMPLETED);
    assertThat(registry.references(object.id())).extracting(StorageObjectRegistry.Reference::kind).contains("CHECKPOINT");
    assertThat(new RawToolResultStore(root,registry).read("chat",ref).rawResult()).isEqualTo("結果");
  }
  StorageObjectRegistry.StoredObject produced(StorageObjectRegistry registry,String name,int bytes)throws Exception {
    Path directory=Files.createDirectories(root.resolve("logs/activity-archives"));Path file=directory.resolve(name+".jsonl");
    Files.writeString(file,"x".repeat(bytes));
    return registry.registerProducedFile(file,"ACTIVITY_RAW",null,null,now.minus(Duration.ofDays(100)),true,List.of());
  }
  @Test void dryRunIsBoundedAndApprovalIsBoundToExactSnapshotAndNeverMovesFiles()throws Exception {
    prepare();var registry=registry();var first=produced(registry,UUID.randomUUID().toString(),100);
    produced(registry,UUID.randomUUID().toString(),100);produced(registry,UUID.randomUUID().toString(),100);
    var planner=new RetentionPlanner(registry);var plan=planner.plan("activity-raw",null,2,150);
    assertThat(plan.candidates()).hasSize(1);assertThat(plan.recoverableBytes()).isEqualTo(100);
    assertThat(planner.approve(plan.id()).snapshotHash()).isEqualTo(plan.snapshotHash());
    assertThat(planner.history()).hasSize(1);assertThat(root.resolve(first.relativePath())).exists();
    try(var files=Files.list(root.resolve("logs/activity-archives"))){assertThat(files.count()).isEqualTo(3);}
    assertThat(root.resolve(".storage/quarantine")).doesNotExist();
  }
  @Test void newlyAddedReferencePinHoldOrChangedFilePreventsStaleApproval()throws Exception {
    prepare();var registry=registry();var object=produced(registry,UUID.randomUUID().toString(),100);
    var planner=new RetentionPlanner(registry);var plan=planner.plan("activity-raw",null,2,150);
    registry.addReference(object.id(),"EXPORT","export");
    assertThatThrownBy(()->planner.approve(plan.id())).isInstanceOf(IllegalStateException.class);
    registry.pin(object.id(),true);registry.legalHold(object.id(),true);
    assertThat(planner.plan("activity-raw",null,2,150).candidates()).isEmpty();
  }
  @Test void policyChangeInvalidatesApprovalAndAutomaticRetentionDefaultsOff()throws Exception {
    prepare();var registry=registry();produced(registry,UUID.randomUUID().toString(),100);
    var planner=new RetentionPlanner(registry);var plan=planner.plan("activity-raw",null,2,150);
    assertThat(planner.policy("activity-raw").automatic()).isFalse();
    planner.updatePolicy("activity-raw",Duration.ofDays(365),null,null);
    assertThatThrownBy(()->planner.approve(plan.id())).isInstanceOf(IllegalStateException.class);
    assertThat(planner.policy("sessions").retentionSeconds()).isNull();
    assertThat(planner.policy("voice-models").automatic()).isFalse();
  }
  @Test void checksumChangeAndPinOrHoldIndividuallyInvalidateApproval()throws Exception {
    prepare();var registry=registry();var object=produced(registry,UUID.randomUUID().toString(),100);var planner=new RetentionPlanner(registry);
    var plan=planner.plan("activity-raw",null,2,150);Files.writeString(root.resolve(object.relativePath()),"y".repeat(100));
    assertThatThrownBy(()->planner.approve(plan.id())).isInstanceOf(IllegalStateException.class);
    Files.writeString(root.resolve(object.relativePath()),"x".repeat(100));registry.pin(object.id(),true);
    assertThat(planner.plan("activity-raw",null,2,150).protectedReasons()).containsKey("pinned");
    registry.pin(object.id(),false);registry.legalHold(object.id(),true);
    assertThat(planner.plan("activity-raw",null,2,150).protectedReasons()).containsKey("legal-hold");
  }
  @Test void quotaCanSelectOldestUnreferencedBodyWithoutExpiringIt()throws Exception {
    prepare();var registry=registry();produced(registry,UUID.randomUUID().toString(),100);produced(registry,UUID.randomUUID().toString(),100);
    var planner=new RetentionPlanner(registry);planner.updatePolicy("activity-raw",Duration.ofDays(365),150L,1L);
    assertThat(planner.plan("activity-raw",null,10,1000).candidates()).hasSize(1);
  }
  @Test void checkpointSaveRegistersReferenceBeforeItIsPublishedAndLegacyMigrationImportsIt()throws Exception {
    String ref=new RawToolResultStore(root).save("chat","run","tool","call","旧結果");
    var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("memory-consolidation.db"));
    var repository=new dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository(source,new dev.mikoto2000.rei.checkpoint.CheckpointProperties());
    var state=dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.initial("task","project","chat","run",root,"question");
    var fields=repository.fields(state);fields.put("evidence",List.of(Map.of("origin","RAW_RESULT_REFERENCE","summary",ref,"runId","run","toolCallId","call")));state=repository.fields(fields);
    repository.save(state,0,"legacy");prepare();var registry=registry();var object=registry.rawObject("chat",ref).orElseThrow();
    assertThat(registry.references(object.id())).extracting(StorageObjectRegistry.Reference::kind).contains("CHECKPOINT");
    repository.setStorageObjectRegistry(registry);String newRef=new RawToolResultStore(root,registry).save("chat","run","tool","call2","新結果");
    fields.put("evidence",List.of(Map.of("origin","RAW_RESULT_REFERENCE","summary",newRef,"runId","run","toolCallId","call2")));state=repository.fields(fields);
    repository.save(state,1,"new");assertThat(registry.references(registry.rawObject("chat",newRef).orElseThrow().id())).extracting(StorageObjectRegistry.Reference::kind).contains("CHECKPOINT");
    assertThat(repository.get("project","task").revision()).isEqualTo(2);
  }
  @Test void producerCannotAdoptAnExistingUnknownFileOrRegisterOutsideManagedPaths()throws Exception {
    String ref=new RawToolResultStore(root).save("chat","run","tool","call","旧結果");prepare();var registry=registry();
    new RawToolResultStore(root,registry).save("chat","run","tool","call","旧結果");
    assertThat(registry.rawObject("chat",ref).orElseThrow().originVerified()).isFalse();
    Path external=Files.writeString(root.resolve("export.jsonl"),"export");
    assertThatThrownBy(()->registry.registerProducedFile(external,"ACTIVITY_RAW",null,null,now,true,List.of())).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void versionTwoUpgradeDoesNotReimportChangedLegacySessionJsonAndKeepsRollbackAtomic()throws Exception {
    prepare();
    try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("storage.db"));var sql=db.createStatement()) {
      for(String table:List.of("stored_objects","object_references","retention_policies","retention_plans","retention_candidates","retention_approvals","agent_events","event_sequences","event_cursors","event_imports","retention_executions","retention_execution_items","retention_purge_approvals","activity_segments","retention_automatic_consents","retention_automatic_runs","conversation_admissions","chat_receipts"))sql.execute("DROP TABLE IF EXISTS "+table);
      sql.execute("DELETE FROM storage_migrations WHERE version>2");sql.execute("PRAGMA user_version=2");
    }
    Files.writeString(root.resolve("sessions.json"),"not a session array");
    try(var migration=new StorageMigrationCoordinator(root,(stage,backup)->{if(stage==StorageMigrationCoordinator.Stage.ROWS_IMPORTED)throw new java.io.IOException("interrupt");})) {
      assertThatThrownBy(migration::prepare).isInstanceOf(java.io.IOException.class);
    }
    assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isEqualTo(2);
    prepare();assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isEqualTo(StorageMigrationCoordinator.SCHEMA_VERSION);
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo("not a session array");
    try(var backups=Files.list(root.resolve(".storage/backups"))){assertThat(backups.count()).isEqualTo(2);}
  }
  @Test void approvalPersistsAcrossRestartAndStatusDoesNotCreatePlanOrHashBodies()throws Exception {
    prepare();var registry=registry();var object=produced(registry,UUID.randomUUID().toString(),100);var planner=new RetentionPlanner(registry);var plan=planner.plan("activity-raw",null,2,150);planner.approve(plan.id());
    var reopened=new RetentionPlanner(registry());assertThat(reopened.get(plan.id())).isEqualTo(plan);
    Files.delete(root.resolve(object.relativePath()));var status=reopened.status(null);
    assertThat(status.objects()).extracting(RetentionPlanner.ObjectUsage::recordedBodyBytes).containsExactly(100L);
    assertThat(reopened.history()).hasSize(1);assertThatThrownBy(()->reopened.approve(plan.id())).isInstanceOf(IllegalStateException.class);
  }
  @Test void malformedCheckpointSourceStopsMigrationAndPreservesOriginalDatabaseBytes()throws Exception {
    var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("memory-consolidation.db"));
    var repository=new dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository(source,new dev.mikoto2000.rei.checkpoint.CheckpointProperties());
    repository.save(dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.initial("task","project","chat","run",root,"question"),0,"legacy");
    try(var connection=source.getConnection();var sql=connection.createStatement()){sql.execute("UPDATE checkpoint_revisions SET snapshot='broken'");}
    String original=StorageBackup.hash(root.resolve("memory-consolidation.db"));
    assertThatThrownBy(this::prepare).isInstanceOf(java.io.IOException.class);
    assertThat(StorageBackup.hash(root.resolve("memory-consolidation.db"))).isEqualTo(original);
    assertThat(StorageMigrationCoordinatorTest.version(root.resolve("storage.db"))).isZero();
  }
  @Test void checkpointFileAndEventReferencesProtectTheActualOwnerEvenForUnprefixedSessions()throws Exception {
    prepare();var registry=registry();var object=produced(registry,UUID.randomUUID().toString(),100);
    String project=UUID.randomUUID().toString();String event=UUID.randomUUID().toString();
    var state=dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.initial("task",project,"chat","run",root,"question");
    var fields=StorageDatabase.JSON.convertValue(state,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    fields.put("files",Map.of(root.resolve(object.relativePath()).toString(),"sha256:"+object.sha256()));
    fields.put("evidence",List.of(Map.of("origin","TOOL_CONFIRMED","summary","done","eventId",event)));
    registry.protectCheckpoint(StorageDatabase.JSON.convertValue(fields,dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.class));
    assertThat(new RetentionPlanner(registry).plan("activity-raw",null,10,1000).candidates()).isEmpty();
    assertThat(registry.references("event:"+project+":"+event)).extracting(StorageObjectRegistry.Reference::kind).contains("CHECKPOINT");
  }
  @Test void relativeCheckpointFilePathsAreUnknownAndProtectObjectsInsteadOfGuessingACwd()throws Exception {
    prepare();var registry=registry();produced(registry,UUID.randomUUID().toString(),100);
    var state=dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.initial("task",UUID.randomUUID().toString(),"chat","run",root,"question");
    var fields=StorageDatabase.JSON.convertValue(state,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});fields.put("files",Map.of("relative/file","unknown"));
    registry.protectCheckpoint(StorageDatabase.JSON.convertValue(fields,dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.class));
    assertThat(new RetentionPlanner(registry).plan("activity-raw",null,10,1000).protectedReasons()).containsKey("unresolved-checkpoint-path");
  }
  @Test void aPreviouslyReadObjectCannotHideANewPinFromProtectionQueries()throws Exception {
    prepare();var registry=registry();var object=produced(registry,UUID.randomUUID().toString(),100);registry.pin(object.id(),true);
    assertThat(registry.protectionReason(object)).contains("pinned");
  }
}
