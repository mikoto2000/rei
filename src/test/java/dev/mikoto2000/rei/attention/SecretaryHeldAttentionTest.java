package dev.mikoto2000.rei.attention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.secretary.SecretaryContext;
import dev.mikoto2000.rei.secretary.SecretaryNotifications;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class SecretaryHeldAttentionTest {
  @TempDir Path root;

  @Test
  void savedHeldMessagesAreScopedAndReadingDoesNotAcknowledgeThem() {
    var clock = Clock.systemUTC();
    var inbox =
        new AttentionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("inbox.db")), clock);
    var item =
        inbox.createSecretary(
            new SecretaryNotifications.Notice(
                "one", "p", null, "NORMAL", "result awaiting review", 2, "INBOX", clock.instant()));
    inbox.hold("p", item.id());
    var beans = new StaticListableBeanFactory();
    beans.addBean("inbox", inbox);
    var source = new SecretaryHeldAttention(beans.getBeanProvider(AttentionRepository.class));
    var context = source.context("p", LocalDate.now(clock), ZoneId.of("UTC"));
    assertEquals(1, context.size());
    assertEquals(SecretaryContext.Reliability.UNKNOWN, context.getFirst().reliability());
    assertEquals("HELD", inbox.get("p", item.id()).status());
    assertTrue(source.context("other", LocalDate.now(clock), ZoneId.of("UTC")).isEmpty());
  }
}
