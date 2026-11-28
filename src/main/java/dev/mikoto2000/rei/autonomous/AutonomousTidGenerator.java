package dev.mikoto2000.rei.autonomous;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.regex.Pattern;

/** Timestamp record keys for the fixed Bluesky post collection. */
public final class AutonomousTidGenerator {
  private static final String ALPHABET = "234567abcdefghijklmnopqrstuvwxyz";
  private static final Pattern SYNTAX =
      Pattern.compile("[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}");
  private static final long MAX_MICROS = (1L << 53) - 1;
  private final Clock clock;
  private final int clockId;
  private long latestMicros = -1;

  public AutonomousTidGenerator(Clock clock) {
    this(clock, new SecureRandom().nextInt(1024));
  }

  public AutonomousTidGenerator(Clock clock, int clockId) {
    this.clock = Objects.requireNonNull(clock);
    if (clockId < 0 || clockId > 1023) throw new IllegalArgumentException("Invalid TID clock ID");
    this.clockId = clockId;
  }

  public synchronized String next() {
    var instant = clock.instant();
    long seconds = instant.getEpochSecond();
    if (seconds < 0 || seconds > MAX_MICROS / 1_000_000)
      throw new IllegalArgumentException("TID timestamp is out of range");
    long micros = seconds * 1_000_000 + instant.getNano() / 1000;
    micros = Math.max(micros, latestMicros + 1);
    if (micros > MAX_MICROS) throw new IllegalArgumentException("TID timestamp is out of range");
    latestMicros = micros;
    long value = (micros << 10) | clockId;
    var encoded = new char[13];
    for (int i = encoded.length - 1; i >= 0; i--) {
      encoded[i] = ALPHABET.charAt((int) (value & 31));
      value >>>= 5;
    }
    return new String(encoded);
  }

  public static boolean valid(String key) {
    return key != null && SYNTAX.matcher(key).matches();
  }
}
