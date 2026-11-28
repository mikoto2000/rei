package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutonomousTidGeneratorTest {
  @Test
  void epochAndClockIdentifierHaveTheSpecifiedSortableBase32Encoding() {
    var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    assertThat(new AutonomousTidGenerator(clock, 0).next()).isEqualTo("2222222222222");
    assertThat(new AutonomousTidGenerator(clock, 1023).next()).isEqualTo("22222222222zz");
  }

  @Test
  void repeatedOrBackwardsClockValuesStillProduceIncreasingKeys() {
    var instant = new AtomicReference<>(Instant.parse("2026-11-28T06:00:00.123456Z"));
    var generator = new AutonomousTidGenerator(new MutableClock(instant, ZoneOffset.UTC), 7);
    var first = generator.next();
    var second = generator.next();
    instant.set(instant.get().minusSeconds(3600));
    var third = generator.next();
    instant.set(instant.get().plusSeconds(7200));
    var fourth = generator.next();
    assertThat(first).isLessThan(second);
    assertThat(second).isLessThan(third);
    assertThat(third).isLessThan(fourth);
    assertThat(java.util.List.of(first, second, third, fourth))
        .allMatch(AutonomousTidGenerator::valid);
  }

  @Test
  void concurrentCallersNeverReuseAKey() throws Exception {
    var generator = new AutonomousTidGenerator(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 3);
    try (var executor = Executors.newFixedThreadPool(4)) {
      var futures =
          IntStream.range(0, 1000).mapToObj(i -> executor.submit(generator::next)).toList();
      var keys = new HashSet<String>();
      for (var future : futures) assertThat(keys.add(future.get())).isTrue();
      assertThat(keys).hasSize(1000).allMatch(AutonomousTidGenerator::valid);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 1024, Integer.MAX_VALUE})
  void clockIdentifierMustFitTenBits(int clockId) {
    assertThatThrownBy(() -> new AutonomousTidGenerator(Clock.systemUTC(), clockId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void timestampsOutsideTheFiftyThreeBitRangeAreRejected() {
    for (var instant : java.util.List.of(Instant.ofEpochSecond(-1), micros(1L << 53))) {
      var generator = new AutonomousTidGenerator(Clock.fixed(instant, ZoneOffset.UTC), 0);
      assertThatThrownBy(generator::next).isInstanceOf(IllegalArgumentException.class);
    }
    var last = new AutonomousTidGenerator(Clock.fixed(micros((1L << 53) - 1), ZoneOffset.UTC), 0);
    assertThat(AutonomousTidGenerator.valid(last.next())).isTrue();
    assertThatThrownBy(last::next).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"3jzfcijpj2z2a", "7777777777777", "3zzzzzzzzzzzz", "2222222222222"})
  void acceptsOfficialTidExamples(String key) {
    assertThat(AutonomousTidGenerator.valid(key)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "3jzfcijpj2z21",
        "0000000000000",
        "3JZFCIJPJ2Z2A",
        "3jzfcijpj2z2aa",
        "3jzfcijpj2z2",
        "222",
        "3jzf-cij-pj2z-2a",
        "zzzzzzzzzzzzz",
        "kjzfcijpj2z2a",
        "60aa5c2b-6446-4bb5-bc3d-c9ff8f924b39",
        "",
        "3jzfcijpj2z2a\n"
      })
  void rejectsNonTidRecordKeys(String key) {
    assertThat(AutonomousTidGenerator.valid(key)).isFalse();
  }

  @Test
  void nullIsNotATid() {
    assertThat(AutonomousTidGenerator.valid(null)).isFalse();
  }

  private static Instant micros(long value) {
    return Instant.ofEpochSecond(value / 1_000_000, (value % 1_000_000) * 1000);
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
      this.instant = instant;
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId next) {
      return new MutableClock(instant, next);
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
