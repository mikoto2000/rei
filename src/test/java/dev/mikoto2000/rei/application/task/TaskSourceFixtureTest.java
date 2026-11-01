package dev.mikoto2000.rei.application.task;

import static dev.mikoto2000.rei.application.task.TaskSourcePaginationTest.populate;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Fixture writes are durable and isolated before pagination reads begin. */
@Tag("integration")
class TaskSourceFixtureTest {
  /** An independent on-disk database for this contract. */
  @TempDir Path root;
  /** Fixture writes commit before fresh readers, and failed preparation leaves no partial rows. */
  @Test
  void fixturePopulationCommitsAndRollsBackWithoutLeakingTransaction() throws Exception {
    var data = new SQLiteDataSource();
    data.setUrl("jdbc:sqlite:" + root.resolve("fixture-contract.db"));
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(data);
    jdbc.sql("CREATE TABLE fixture_rows(value TEXT)").update();
    populate(data, () -> jdbc.sql("INSERT INTO fixture_rows VALUES('committed')").update());
    try (var connection = data.getConnection();
        var query = connection.createStatement();
        var rows = query.executeQuery("SELECT value FROM fixture_rows")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("committed");
      assertThat(rows.next()).isFalse();
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> populate(data, () -> {
              jdbc.sql("INSERT INTO fixture_rows VALUES('rolled-back')").update();
              throw new IllegalStateException("failed fixture");
            }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("failed fixture");
    try (var connection = data.getConnection();
        var query = connection.createStatement();
        var rows = query.executeQuery("SELECT COUNT(*) FROM fixture_rows")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(1);
    }
    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()).isFalse();
  }

}
