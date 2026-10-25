package dev.mikoto2000.rei.life;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;

class LifeYamlSettingsTest {
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(LifeProperties.class)
  @Import(LifeContext.class)
  static class Config {}

  final LifeContextTest.Memory store = new LifeContextTest.Memory();

  ApplicationContextRunner runner(String yaml) {
    return new ApplicationContextRunner()
        .withUserConfiguration(Config.class)
        .withBean(LifeContext.Store.class, () -> store)
        .withBean(
            Clock.class, () -> Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneId.of("UTC")))
        .withInitializer(
            context -> {
              try {
                for (var source :
                    new YamlPropertySourceLoader()
                        .load("test", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8))))
                  context.getEnvironment().getPropertySources().addFirst(source);
              } catch (java.io.IOException invalid) {
                throw new java.io.UncheckedIOException(invalid);
              }
            });
  }

  @Test
  void yamlOwnsExplicitFieldsWhileUnspecifiedFieldsKeepThePersistentSettings() {
    var initial = new LifeContext(store, Clock.systemUTC());
    var s = initial.settings();
    initial.configure(
        new LifeContext.Settings(
            0,
            s.zone(),
            s.workDays(),
            "10:00",
            "18:00",
            List.of(),
            List.of(),
            List.of(),
            List.of(new LifeContext.Background("p", "t")),
            null));
    runner(
            """
        rei:
          life:
            work-start: '08:00'
            work-end: '17:00'
        """)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var life = context.getBean(LifeContext.class);
              assertTrue(life.status().working());
              assertEquals("08:00", life.settings().workStart());
              assertEquals(
                  List.of(new LifeContext.Background("p", "t")), life.settings().background());
              assertEquals("10:00", store.value.workStart()); // no startup write
              var current = life.settings();
              assertThrows(
                  IllegalArgumentException.class,
                  () ->
                      life.configure(
                          new LifeContext.Settings(
                              current.revision(),
                              current.zone(),
                              current.workDays(),
                              "09:00",
                              current.workEnd(),
                              current.notifications(),
                              current.rest(),
                              current.personal(),
                              current.background(),
                              null)));
              assertEquals("10:00", store.value.workStart());
            });
    runner("rei:\n  life:\n    work-start: '11:00'\n")
        .run(
            context -> {
              assertEquals("11:00", context.getBean(LifeContext.class).settings().workStart());
              assertFalse(context.getBean(LifeContext.class).status().working());
            });
  }

  @Test
  void actualYamlBindsDaysIntervalsAndBackgroundScopesWithoutInventingRest() {
    runner(
            """
        rei:
          life:
            zone: Asia/Tokyo
            work-days: [MONDAY, TUESDAY]
            work-start: '09:00'
            work-end: '18:00'
            personal:
              - days: [MONDAY]
                start: '19:00'
                end: '21:00'
            background:
              - project: p
                task: t
        """)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var s = context.getBean(LifeContext.class).settings();
              assertEquals(2, s.workDays().size());
              assertEquals("19:00", s.personal().getFirst().start());
              assertTrue(s.rest().isEmpty());
              assertEquals(List.of(new LifeContext.Background("p", "t")), s.background());
            });
  }

  @Test
  void malformedOrMisspelledYamlFailsStartupInsteadOfSilentlyUsingAnUnprotectedDefault() {
    runner("rei:\n  life:\n    zone: not-a-zone\n")
        .run(context -> assertNotNull(context.getStartupFailure()));
    runner("rei:\n  life:\n    work-strat: '08:00'\n")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void explicitEmptyYamlListsClearStoredValuesAndTheNormalCliNeedsNoProjectForLifeSettings() {
    var initial = new LifeContext(store, Clock.systemUTC());
    var s = initial.settings();
    initial.configure(
        new LifeContext.Settings(
            0,
            s.zone(),
            s.workDays(),
            s.workStart(),
            s.workEnd(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new LifeContext.Background("p", "t")),
            null));
    runner("rei:\n  life:\n    background: []\n    work-days: []\n")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var life = context.getBean(LifeContext.class);
              assertTrue(life.settings().background().isEmpty());
              assertTrue(life.settings().workDays().isEmpty());
              var command = new dev.mikoto2000.rei.secretary.SecretaryCommand();
              org.springframework.test.util.ReflectionTestUtils.setField(command, "life", life);
              var cli = new picocli.CommandLine(command);
              var text = new java.io.StringWriter();
              cli.setOut(new java.io.PrintWriter(text));
              cli.setErr(new java.io.PrintWriter(text));
              assertEquals(0, cli.execute("life-settings"));
              assertTrue(text.toString().contains("workDays"));
              assertEquals(2, cli.execute("list"));
            });
  }
}
