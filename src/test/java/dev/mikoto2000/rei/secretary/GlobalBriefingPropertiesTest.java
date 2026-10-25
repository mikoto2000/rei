package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GlobalBriefingPropertiesTest {
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(GlobalBriefingProperties.class)
  static class Config {}

  final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void defaultsUseAllRegisteredProjectsWithoutInventingTimesOrIndependentGroups() {
    runner.run(
        context -> {
          assertNull(context.getStartupFailure());
          var value = context.getBean(GlobalBriefingProperties.class);
          assertFalse(value.enabled());
          assertEquals("", value.morningTime());
          assertEquals("", value.eveningTime());
          assertEquals(java.util.List.of(), value.projects());
          assertEquals(java.util.List.of(), value.independentProjects());
        });
  }

  @Test
  void enabledBriefingsNeedTimesButNoProjectAllowlist() {
    runner
        .withPropertyValues(
            "rei.secretary.briefings.enabled=true", "rei.secretary.briefings.morning-time=08:00")
        .run(c -> assertNull(c.getStartupFailure()));
    runner
        .withPropertyValues("rei.secretary.briefings.enabled=true")
        .run(c -> assertNotNull(c.getStartupFailure()));
  }

  @Test
  void invalidTimesMisspelledSettingsAndDuplicateIndependentProjectsFailStartup() {
    runner
        .withPropertyValues("rei.secretary.briefings.morning-time=25:00")
        .run(c -> assertNotNull(c.getStartupFailure()));
    runner
        .withPropertyValues("rei.secretary.briefings.mornig-time=08:00")
        .run(c -> assertNotNull(c.getStartupFailure()));
    runner
        .withPropertyValues(
            "rei.secretary.briefings.independent-projects[0]=p",
            "rei.secretary.briefings.independent-projects[1]=p")
        .run(c -> assertNotNull(c.getStartupFailure()));
    runner
        .withPropertyValues("rei.secretary.briefings.independent-projects[0]=p")
        .run(
            c -> {
              assertNull(c.getStartupFailure());
              assertEquals(
                  java.util.List.of("p"),
                  c.getBean(GlobalBriefingProperties.class).independentProjects());
            });
  }
}
