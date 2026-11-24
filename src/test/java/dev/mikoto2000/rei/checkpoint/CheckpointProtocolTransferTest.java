package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ConcurrentModificationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

/** 再開用の記録履歴の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class CheckpointProtocolTransferTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う取得元。 */
  SQLiteDataSource source;

  /** 検証に使う設定。 */
  CheckpointProperties settings;

  /** 検証に使うリポジトリ。 */
  PersistentCheckpointRepository repo;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("history.db"));
    settings = new CheckpointProperties();
    repo = new PersistentCheckpointRepository(source, settings);
  }

  @ParameterizedTest @ValueSource(strings = {"compatible", "missing", "changed"})
  void checkpointRevisionTransfersItsPinsAfterTheOriginalRunHasEnded(String condition) throws Exception {
    var payloadSource = new SQLiteDataSource();
    payloadSource.setUrl("jdbc:sqlite:" + root.resolve("protocol.db"));
    var payloads = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(payloadSource);
    var beans =
        new org.springframework.beans.factory.support.StaticListableBeanFactory(
            java.util.Map.of("payloads", payloads));
    repo.configureModelMessagePayloads(
        beans.getBeanProvider(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class));
    var scope =
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope(
            "project:project", root.toString(), "session");
    var message =
        org.springframework.ai.chat.messages.AssistantMessage.builder()
            .content("fixture answer")
            .properties(
                java.util.Map.of(
                    "rei.llm.replay-origin-owner",
                    "owner",
                    "rei.llm.replay-origin-endpoint",
                    "endpoint"))
            .build();
    var ref = payloads.save(scope, "run-native", "first", message);
    payloads.publish(
        scope,
        ref,
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
        "run-native");
    var history =
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History(
            1,
            "run-native",
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin(
                "owner", "endpoint"),
            List.of(
                new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry(
                    "first", ref)));
    var fields = repo.fields(initial("native"));
    fields.put(
        "progress",
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState(
            null, null, 0, 0, 0, null, history));
    settings.setMaxRevisions(3);
    repo.save(repo.fields(fields), 0, "native-1");
    payloads.release(
        scope,
        ref,
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN,
        "run-native");
    try (var db = DriverManager.getConnection(payloadSource.getUrl());
        var statement = db.createStatement()) {
      statement.executeUpdate("UPDATE model_message_payloads SET staged_until=0");
    }
    assertEquals(0, payloads.collectExpired(), "retained checkpoint must pin the payload");
    assertEquals(
        history,
        new PersistentCheckpointRepository(source, settings)
            .get("project", "native")
            .progress()
            .protocolHistory());
    assertEquals("fixture answer", payloads.load(scope, ref).getText());
    var resumed = repo.get("project", "native").resume("resumed-run");
    if (!condition.equals("compatible")) {
      if (condition.equals("missing")) payloads.releaseCheckpointHistory(List.of(
          dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.checkpointOwner("project", "native", 1)));
      else {
        var unpublished = payloads.save(scope,"run-native","unpublished",new org.springframework.ai.chat.messages.UserMessage("unpublished replacement"));
        var changed = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History(1, history.run(),history.origin(),
            List.of(new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry("changed/USER",unpublished)));
        var altered = repo.fields(resumed);
        altered.put("progress",new dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState(null,null,0,0,0,null,changed));
        resumed = repo.fields(altered);
      }
      var rejected = resumed;
      assertThrows(IllegalStateException.class, () -> repo.save(rejected,1,"native-resume"));
      assertEquals(1,repo.get("project","native").revision());
      return;
    }
    repo.save(resumed, 1, "native-resume");
    assertEquals(0, payloads.collectExpired(), "new checkpoint revision must replace the original pin");
    String nextOwner = dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.checkpointOwner("project", "native", 2);
    assertEquals("fixture answer", payloads.load(scope, ref,
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, nextOwner).getText());
    assertThrows(IllegalStateException.class, () -> payloads.load(scope, ref,
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.RUN, "run-native"));
    payloads.releaseCheckpointHistory(List.of(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.checkpointOwner("project", "native", 1)));
    assertEquals(0, payloads.collectExpired(), "new revision pin survives the old revision's release");
    payloads.releaseCheckpointHistory(List.of(nextOwner));
    assertEquals(1, payloads.collectExpired(), "no Run lease was resurrected by transfer");
  }

  PersistentCheckpoint initial(String task) { return PersistentCheckpoint.initial(task,"project","session","run-"+task,root,"request-"+task); }
}
