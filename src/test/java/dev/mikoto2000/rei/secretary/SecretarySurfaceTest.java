package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.application.session.ConversationAccepted;
import dev.mikoto2000.rei.application.session.SecretaryIntake;
import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationConfiguration;
import dev.mikoto2000.rei.web.SecretaryController;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import picocli.CommandLine;

@Tag("integration")
class SecretarySurfaceTest {
  @TempDir Path root;

  AnnotationConfigApplicationContext context(boolean enabled, boolean extraction) {
    var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "rei.data-dir",
                    root.toString(),
                    "rei.secretary.enabled",
                    enabled,
                    "rei.secretary.extraction-enabled",
                    extraction)));
    context.registerBean(Clock.class, Clock::systemUTC);
    context.register(
        StorageMigrationConfiguration.class,
        SqliteSecretaryDatabase.class,
        SecretaryLedger.class,
        SecretaryIntake.class,
        SecretaryCommand.class);
    context.refresh();
    return context;
  }

  @Test
  void disabledFeaturesDoNotObserveOrRequireExternalServices() {
    try (var context = context(false, true)) {
      assertTrue(context.getBeansOfType(SecretaryLedger.class).isEmpty());
      assertTrue(context.getBeansOfType(SecretaryIntake.class).isEmpty());
      var command = new CommandLine(context.getBean(SecretaryCommand.class));
      command.setErr(new PrintWriter(new StringWriter()));
      assertEquals(2, command.execute("list", "--project", "p"));
    }
  }

  @Test
  void eventsCliAndApiShareLedgerWithOptionalExtraction() {
    try (var context = context(true, true)) {
      context.publishEvent(new ConversationAccepted("p", "s", "run1", "来週タスクを進めたい"));
      var ledger = context.getBean(SecretaryLedger.class);
      var task = ledger.list("p", 0, 10).getFirst();
      assertEquals(SecretaryTask.Status.CANDIDATE, task.status());
      var output = new StringWriter();
      var command = new CommandLine(context.getBean(SecretaryCommand.class));
      command.setOut(new PrintWriter(output));
      assertEquals(0, command.execute("approve", task.id(), "--project", "p", "--revision", "0"));
      assertTrue(output.toString().contains("OPEN"));
      var api = new SecretaryController(ledger);
      assertEquals(SecretaryTask.Status.OPEN, api.get(task.id(), "p").status());
      assertFalse(api.sources(task.id(), "p").isEmpty());
      assertEquals(
          SecretaryTask.Status.DONE,
          api.transition(
                  task.id(), new SecretaryController.Transition("p", 1, SecretaryTask.Status.DONE))
              .status());
      context.publishEvent(new ConversationAccepted("p", "s", "run2", "/task add 作成したい"));
      assertEquals(1, ledger.list("p", 0, 10).size());
    }
    try (var context = context(true, false)) {
      context.publishEvent(new ConversationAccepted("p", "s", "run3", "別のタスクを進めたい"));
      assertEquals(1, context.getBean(SecretaryLedger.class).list("p", 0, 10).size());
    }
  }
}
