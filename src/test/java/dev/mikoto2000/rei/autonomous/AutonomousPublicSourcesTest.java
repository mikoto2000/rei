package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AutonomousPublicSourcesTest {
  @TempDir Path root;
  AutonomousPublicSources sources;

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    sources =
        new AutonomousPublicSources(
            new StorageDatabase(root),
            Clock.fixed(Instant.parse("2026-11-28T00:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void onlyExplicitlyPermittedTextAndVersionedIdentifiersEnterTheSnapshot() {
    sources.register("PRIVATE_TOPIC_MARKER", false, "PRIVATE_ACTOR_MARKER");
    var released = sources.register("公開利用を許可した話題", true, "PRIVATE_ACTOR_MARKER");
    var snapshot = sources.publicSnapshot();
    assertThat(snapshot)
        .singleElement()
        .satisfies(item -> {
          assertThat(item.id()).isEqualTo("topic:" + released.id() + ":1");
          assertThat(item.text()).isEqualTo("公開利用を許可した話題");
        });
    assertThat(snapshot.toString()).doesNotContain("PRIVATE_TOPIC_MARKER", "PRIVATE_ACTOR_MARKER");
  }

  @Test
  void filteringRowsAndProjectingPublicColumnsHappensInsideTheDatabase() throws Exception {
    sources.register("not for publication", false, "private actor");
    sources.register("public topic", true, "private actor");
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      sql.execute("ALTER TABLE autonomous_public_sources RENAME TO source_records_fixture");
      // Evaluating a private row's body or any actor column fails instead of exposing its value.
      sql.execute(
          """
          CREATE VIEW autonomous_public_sources AS
          SELECT id,revision,is_current,
            CASE WHEN public_use=0 THEN json_extract('private-body-must-not-be-read','$')
              ELSE body END AS body,
            public_use,updated,json_extract('private-actor-must-not-be-read','$') AS actor
          FROM source_records_fixture
          """);
      assertThatThrownBy(
              () -> {
                try (var rows =
                    sql.executeQuery(
                        "SELECT body FROM autonomous_public_sources WHERE public_use=0")) {
                  rows.next();
                }
              })
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () -> {
                try (var rows =
                    sql.executeQuery(
                        "SELECT actor FROM autonomous_public_sources WHERE public_use=1")) {
                  rows.next();
                }
              })
          .isInstanceOf(java.sql.SQLException.class);
    }
    assertThat(sources.publicSnapshot())
        .singleElement()
        .satisfies(item -> assertThat(item.text()).isEqualTo("public topic"));
  }

  @Test
  void invalidSourceTextIsRejectedBeforeWriting() {
    for (String text : new String[] {null, "", "　", "\uD800", "x".repeat(8193), "界".repeat(3000)}) {
      assertThatThrownBy(() -> sources.register(text, true, "operator"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(sources.publicSnapshot()).isEmpty();
  }

  @Test
  void invalidRegistrationActorIsRejectedBeforeWriting() {
    for (String actor :
        new String[] {null, "", " ", "operator\nadmin", "x".repeat(129), "\uDC00"}) {
      assertThatThrownBy(() -> sources.register("public topic", true, actor))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(sources.publicSnapshot()).isEmpty();
  }

  @Test
  void publicSnapshotHasABoundedSourceCount() {
    for (int index = 0; index < 40; index++) sources.register("topic " + index, true, "operator");
    assertThat(sources.publicSnapshot()).hasSize(32);
  }

  @Test
  void publicSnapshotHasABoundedUtf8Budget() {
    for (int index = 0; index < 8; index++) sources.register("x".repeat(8192), true, "operator");
    var snapshot = sources.publicSnapshot();
    assertThat(snapshot).hasSize(4);
    assertThat(
            snapshot.stream()
                .mapToInt(item -> item.text().getBytes(StandardCharsets.UTF_8).length)
                .sum())
        .isEqualTo(32768);
  }
}
