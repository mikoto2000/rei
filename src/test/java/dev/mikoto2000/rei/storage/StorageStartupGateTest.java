package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class StorageStartupGateTest {
  @TempDir Path root;
  static final AtomicInteger constructed = new AtomicInteger();
  @Configuration @Import(StorageMigrationConfiguration.class) static class Config {
    @Bean Object repository(org.springframework.core.env.Environment env) throws Exception {
      constructed.incrementAndGet();
      assertThat(StorageMigrationCoordinatorTest.version(Path.of(env.getProperty("rei.data-dir")).resolve("storage.db"))).isEqualTo(StorageMigrationCoordinator.SCHEMA_VERSION);
      return new Object();
    }
  }
  AnnotationConfigApplicationContext context() {
    var context = new AnnotationConfigApplicationContext();
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of("rei.data-dir",root.toString())));
    context.register(Config.class); return context;
  }
  @Test void gateRunsBeforeRepositoryConstructionAndSameVersionContextsShareLease() {
    constructed.set(0);
    try (var first = context(); var second = context()) {
      first.refresh(); second.refresh(); assertThat(constructed.get()).isEqualTo(2);
      assertThatThrownBy(() -> new StorageMigrationCoordinator(root)).isInstanceOf(java.io.IOException.class);
    }
    assertThatCode(() -> { try (var migration = new StorageMigrationCoordinator(root)) { migration.prepare(); } }).doesNotThrowAnyException();
  }
  @Test void incompatibleSchemaPreventsEveryOrdinaryBeanFromStarting() throws Exception {
    constructed.set(0);
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db")); var statement = db.createStatement()) {
      statement.execute("PRAGMA user_version=99");
    }
    try (var context = context()) { assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(java.io.IOException.class); }
    assertThat(constructed.get()).isZero();
  }
  @Test void sharedLeaseStillChecksSchemaBeforeAnotherContextStarts()throws Exception {
    constructed.set(0);
    try(var first=context();var second=context()) {
      first.refresh();
      try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("storage.db"));var query=db.createStatement()){query.execute("PRAGMA user_version=99");}
      assertThatThrownBy(second::refresh).hasRootCauseInstanceOf(java.io.IOException.class);
      assertThat(constructed.get()).isEqualTo(1);
    }
  }
  @Test void completionMarkerWithoutRequiredSessionSchemaStopsBeforeNormalBeans()throws Exception {
    try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("storage.db"));var query=db.createStatement()){query.execute("DROP TABLE sessions");}
    constructed.set(0);
    try(var context=context()){assertThatThrownBy(context::refresh).hasCauseInstanceOf(java.io.IOException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);}
    assertThat(constructed.get()).isZero();
  }
}
