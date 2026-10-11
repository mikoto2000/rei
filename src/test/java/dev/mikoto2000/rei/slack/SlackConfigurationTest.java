package dev.mikoto2000.rei.slack;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class SlackConfigurationTest {
  @Test void componentScanningNestedConfigurationDoesNotEnableSlackByDefault() {
    new ApplicationContextRunner().withUserConfiguration(SlackConfiguration.HeadlessRuns.class)
        .run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(dev.mikoto2000.rei.application.run.RunRegistry.class);});
  }
  @Test void configuredSlackInShellDoesNotCreateBackendRunBeans() {
    new ApplicationContextRunner().withUserConfiguration(SlackConfiguration.HeadlessRuns.class)
        .withPropertyValues("rei.slack.enabled=true","rei.backend-mode=false")
        .run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(dev.mikoto2000.rei.application.run.RunRegistry.class);});
  }
}
