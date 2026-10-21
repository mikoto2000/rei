package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

/** 保存起動時実行可否の判断の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class StorageStartupGateTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うconstructed。 */
  static final AtomicInteger constructed = new AtomicInteger();

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration
  @Import(StorageMigrationConfiguration.class)
  static class Config {
    /** 検証で使用する保存と検索の窓口を、指定された条件に合わせて用意する。 */
    @Bean
    Object repository(org.springframework.core.env.Environment env) throws Exception {
      constructed.incrementAndGet();
      assertThat(
              StorageMigrationCoordinatorTest.version(
                  Path.of(env.getProperty("rei.data-dir")).resolve("storage.db")))
          .isEqualTo(StorageMigrationCoordinator.SCHEMA_VERSION);
      return new Object();
    }
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  AnnotationConfigApplicationContext context() {
    var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("test", java.util.Map.of("rei.data-dir", root.toString())));
    context.register(Config.class);
    return context;
  }

  /** データ保存の共通処理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void gateRunsBeforeRepositoryConstructionAndSameVersionContextsShareLease() {
    constructed.set(0);
    try (var first = context();
        var second = context()) {
      first.refresh();
      second.refresh();
      assertThat(constructed.get()).isEqualTo(2);
      assertThatThrownBy(() -> new StorageMigrationCoordinator(root))
          .isInstanceOf(java.io.IOException.class);
    }
    assertThatCode(
            () -> {
              try (var migration = new StorageMigrationCoordinator(root)) {
                migration.prepare();
              }
            })
        .doesNotThrowAnyException();
  }

  /** データ保存の共通処理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void incompatibleSchemaPreventsEveryOrdinaryBeanFromStarting() throws Exception {
    constructed.set(0);
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var statement = db.createStatement()) {
      statement.execute("PRAGMA user_version=99");
    }
    try (var context = context()) {
      assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(java.io.IOException.class);
    }
    assertThat(constructed.get()).isZero();
  }

  /** データ保存の共通処理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void sharedLeaseStillChecksSchemaBeforeAnotherContextStarts() throws Exception {
    constructed.set(0);
    try (var first = context();
        var second = context()) {
      first.refresh();
      try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
          var query = db.createStatement()) {
        query.execute("PRAGMA user_version=99");
      }
      assertThatThrownBy(second::refresh).hasRootCauseInstanceOf(java.io.IOException.class);
      assertThat(constructed.get()).isEqualTo(1);
    }
  }

  /** データ保存の共通処理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void completionMarkerWithoutRequiredSessionSchemaStopsBeforeNormalBeans() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var query = db.createStatement()) {
      query.execute("DROP TABLE sessions");
    }
    constructed.set(0);
    try (var context = context()) {
      assertThatThrownBy(context::refresh)
          .hasCauseInstanceOf(java.io.IOException.class)
          .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }
    assertThat(constructed.get()).isZero();
  }
}
