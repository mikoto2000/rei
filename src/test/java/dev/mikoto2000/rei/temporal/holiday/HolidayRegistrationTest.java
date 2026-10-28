package dev.mikoto2000.rei.temporal.holiday;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.temporal.TemporalConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HolidayRegistrationTest {
  @TempDir Path directory;

  @Test
  void createsSharedBeanWithExistingGlobalDirectoryEvenWhenCsvIsMissing() {
    new ApplicationContextRunner()
        .withUserConfiguration(
            TemporalConfiguration.class, HolidayConfiguration.class, JapaneseHolidayTools.class)
        .withSystemProperties("rei.data-dir=" + directory)
        .withPropertyValues("rei.holidays.complete-years=2026-2027")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(JapaneseHolidayService.class)
                  .hasSingleBean(JapaneseHolidayTools.class);
              var status = context.getBean(JapaneseHolidayService.class).getHolidayDataStatus();
              assertThat(status.csvPath()).isEqualTo(directory.resolve("syukujitsu.csv"));
              assertThat(status.loaded()).isFalse();
              assertThat(status.completeYears()).isEmpty();
            });
  }
}
