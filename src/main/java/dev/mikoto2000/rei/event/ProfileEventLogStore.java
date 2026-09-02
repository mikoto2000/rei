package dev.mikoto2000.rei.event;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.mikoto2000.rei.core.datasource.ReiPaths;

@Component
public class ProfileEventLogStore implements AgentEventListener {

  private static final Logger log = LoggerFactory.getLogger(ProfileEventLogStore.class);

  private final Path file;
  private final ObjectMapper objectMapper;
  private final Object writeLock = new Object();

  public ProfileEventLogStore() {
    this(ReiPaths.profileLogPath(), new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  public ProfileEventLogStore(Path file, ObjectMapper objectMapper) {
    this.file = file;
    this.objectMapper = objectMapper;
  }

  public Path file() {
    return file;
  }

  @Override
  public void onEvent(AgentEvent event) {
    append(event);
  }

  public void append(AgentEvent event) {
    if (event == null) {
      return;
    }
    ProfileEventLogEntry entry = toEntry(event);
    synchronized (writeLock) {
      try {
        Files.createDirectories(file.getParent());
        Files.writeString(file, objectMapper.writeValueAsString(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException e) {
        log.warn("Failed to append profile event log: {}", file, e);
      }
    }
  }

  public List<ProfileEventLogEntry> readAll() {
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    List<ProfileEventLogEntry> entries = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        try {
          entries.add(objectMapper.readValue(line, ProfileEventLogEntry.class));
        } catch (IOException e) {
          log.warn("Skipping malformed profile event log line in {}", file);
        }
      }
    } catch (IOException e) {
      log.warn("Failed to read profile event log: {}", file, e);
    }
    entries.sort(Comparator.comparing(ProfileEventLogEntry::timestamp));
    return entries;
  }

  public ProfileSummary summarize() {
    List<ProfileEventLogEntry> entries = readAll();
    Map<String, Long> countsByType = new LinkedHashMap<>();
    Map<String, DurationStats> durationsByType = new LinkedHashMap<>();
    for (ProfileEventLogEntry entry : entries) {
      countsByType.merge(entry.type(), 1L, Long::sum);
      Long duration = durationOf(entry);
      if (duration != null) {
        durationsByType.computeIfAbsent(entry.type(), ignored -> new DurationStats()).add(duration);
      }
    }
    Instant first = entries.isEmpty() ? null : entries.getFirst().timestamp();
    Instant last = entries.isEmpty() ? null : entries.getLast().timestamp();
    return new ProfileSummary(file, entries.size(), first, last, countsByType, durationsByType);
  }

  public List<ProfileBucket> buckets(Duration bucketSize) {
    if (bucketSize == null || bucketSize.isZero() || bucketSize.isNegative()) {
      throw new IllegalArgumentException("bucketSize must be positive");
    }
    List<ProfileEventLogEntry> entries = readAll();
    if (entries.isEmpty()) {
      return List.of();
    }
    Instant start = entries.getFirst().timestamp();
    Map<Long, Long> counts = new LinkedHashMap<>();
    long bucketMillis = bucketSize.toMillis();
    for (ProfileEventLogEntry entry : entries) {
      long index = Math.max(0L, Duration.between(start, entry.timestamp()).toMillis() / bucketMillis);
      counts.merge(index, 1L, Long::sum);
    }
    List<ProfileBucket> buckets = new ArrayList<>();
    for (Map.Entry<Long, Long> count : counts.entrySet()) {
      Instant bucketStart = start.plusMillis(count.getKey() * bucketMillis);
      buckets.add(new ProfileBucket(bucketStart, count.getValue()));
    }
    return buckets;
  }

  private ProfileEventLogEntry toEntry(AgentEvent event) {
    AgentEventPayload payload = event.payload();
    return new ProfileEventLogEntry(
        event.id(),
        event.sequence(),
        event.timestamp(),
        event.type().value(),
        event.version(),
        event.sessionId(),
        event.turnId(),
        event.runId(),
        event.correlationId(),
        event.parentEventId(),
        payload == null ? null : payload.getClass().getSimpleName(),
        summarizePayload(payload));
  }

  private Map<String, Object> summarizePayload(AgentEventPayload payload) {
    if (payload == null) {
      return Map.of();
    }
    Map<String, Object> values = objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() {
    });
    replaceTextWithLength(values, "delta");
    replaceTextWithLength(values, "text");
    replaceTextWithLength(values, "argumentsSummary");
    replaceTextWithLength(values, "resultSummary");
    return values;
  }

  private void replaceTextWithLength(Map<String, Object> values, String key) {
    Object value = values.remove(key);
    if (value instanceof String text) {
      values.put(key + "Length", text.length());
    } else if (value != null) {
      values.put(key, value);
    }
  }

  private Long durationOf(ProfileEventLogEntry entry) {
    if (entry.payload() == null) {
      return null;
    }
    Object duration = entry.payload().get("duration");
    if (duration == null) {
      duration = entry.payload().get("durationMs");
    }
    if (duration instanceof Number number) {
      return number.longValue();
    }
    return null;
  }

  public record ProfileSummary(
      Path file,
      int total,
      Instant first,
      Instant last,
      Map<String, Long> countsByType,
      Map<String, DurationStats> durationsByType) {
  }

  public record ProfileBucket(Instant start, long count) {
  }

  public static final class DurationStats {
    private long count;
    private long totalMillis;
    private long minMillis = Long.MAX_VALUE;
    private long maxMillis = Long.MIN_VALUE;

    void add(long millis) {
      count++;
      totalMillis += millis;
      minMillis = Math.min(minMillis, millis);
      maxMillis = Math.max(maxMillis, millis);
    }

    public long count() {
      return count;
    }

    public long totalMillis() {
      return totalMillis;
    }

    public long minMillis() {
      return count == 0 ? 0L : minMillis;
    }

    public long maxMillis() {
      return count == 0 ? 0L : maxMillis;
    }

    public long averageMillis() {
      return count == 0 ? 0L : Math.round((double) totalMillis / count);
    }
  }
}
